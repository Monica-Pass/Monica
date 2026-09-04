package takagi.ru.monica.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.GoogleDriveAccountSession
import takagi.ru.monica.utils.GoogleDriveAuthManager
import takagi.ru.monica.utils.GoogleDriveAuthorizationStep
import takagi.ru.monica.utils.GoogleDriveKeePassFileSource
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

private enum class KeepassGoogleDriveConnectionState {
    NotConnected,
    Connecting,
    Connected,
    Failed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepassGoogleDriveBrowserBottomSheet(
    viewModel: LocalKeePassViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { GoogleDriveAuthManager(context) }

    var session by remember { mutableStateOf<GoogleDriveAccountSession?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileSourceEntry>>(emptyList()) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isLoadingEntries by remember { mutableStateOf(false) }
    var selectedDatabaseEntry by remember { mutableStateOf<FileSourceEntry?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateDatabaseDialog by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf(KeepassGoogleDriveConnectionState.NotConnected) }

    fun loadDirectory(
        targetPath: String = currentPath,
        targetFolderId: String? = currentFolderId
    ) {
        val activeSession = session ?: return
        coroutineScope.launch {
            isLoadingEntries = true
            browserError = null
            val result = viewModel.listGoogleDriveDirectory(
                accountId = activeSession.accountId,
                currentPath = targetPath,
                currentFolderId = targetFolderId
            )
            result.fold(
                onSuccess = { listing ->
                    currentPath = listing.currentPath
                    currentFolderId = listing.currentFolderId
                    entries = listing.entries
                    connectionState = KeepassGoogleDriveConnectionState.Connected
                },
                onFailure = { error ->
                    browserError = error.message ?: context.getString(R.string.keepass_gdrive_load_files_failed)
                    connectionState = KeepassGoogleDriveConnectionState.Failed
                }
            )
            isLoadingEntries = false
            isConnecting = false
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        coroutineScope.launch {
            isConnecting = true
            browserError = null
            runCatching {
                authManager.completeAuthorization(result.data)
            }.onSuccess { resolvedSession ->
                session = resolvedSession
                currentPath = ""
                currentFolderId = null
                loadDirectory("", null)
            }.onFailure { error ->
                isConnecting = false
                connectionState = KeepassGoogleDriveConnectionState.Failed
                browserError = error.message ?: context.getString(R.string.keepass_gdrive_sign_in_failed)
            }
        }
    }

    fun beginAuthorization(forceSwitch: Boolean) {
        if (activity == null) {
            browserError = context.getString(R.string.keepass_gdrive_activity_missing)
            connectionState = KeepassGoogleDriveConnectionState.Failed
            return
        }

        coroutineScope.launch {
            isConnecting = true
            browserError = null
            connectionState = KeepassGoogleDriveConnectionState.Connecting

            if (forceSwitch) {
                session?.let { activeSession ->
                    runCatching { authManager.revokeAccess(activeSession.accountId) }
                }
                session = null
                entries = emptyList()
                currentPath = ""
                currentFolderId = null
            }

            runCatching { authManager.beginAuthorization() }
                .onSuccess { step ->
                    when (step) {
                        is GoogleDriveAuthorizationStep.Authorized -> {
                            session = step.session
                            currentPath = ""
                            currentFolderId = null
                            loadDirectory("", null)
                        }
                        is GoogleDriveAuthorizationStep.ResolutionRequired -> {
                            authorizationLauncher.launch(
                                IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                            )
                        }
                    }
                }
                .onFailure { error ->
                    isConnecting = false
                    connectionState = KeepassGoogleDriveConnectionState.Failed
                    browserError = error.message ?: context.getString(R.string.keepass_gdrive_sign_in_failed)
                }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { authManager.getCachedSession() }
            .getOrNull()
            ?.let { cached ->
                session = cached
                currentPath = ""
                currentFolderId = null
                loadDirectory("", null)
            }
    }

    val currentPathLabel = if (currentPath.isBlank()) {
        stringResource(R.string.keepass_webdav_root_path)
    } else {
        "/$currentPath"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.keepass_gdrive_attach_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                stringResource(R.string.keepass_gdrive_browser_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CloudQueue, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                session?.displayName ?: stringResource(R.string.keepass_gdrive_not_connected),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                session?.username?.ifBlank {
                                    stringResource(R.string.keepass_gdrive_sign_in_hint)
                                } ?: stringResource(R.string.keepass_gdrive_sign_in_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { beginAuthorization(forceSwitch = session != null) },
                            enabled = !isConnecting && !isLoadingEntries
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (session == null) stringResource(R.string.keepass_gdrive_sign_in_action)
                                else stringResource(R.string.keepass_gdrive_switch_account)
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.keepass_webdav_status_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (connectionState) {
                            KeepassGoogleDriveConnectionState.NotConnected -> stringResource(R.string.keepass_webdav_status_not_connected)
                            KeepassGoogleDriveConnectionState.Connecting -> stringResource(R.string.keepass_webdav_status_connecting)
                            KeepassGoogleDriveConnectionState.Connected -> stringResource(R.string.keepass_webdav_status_connected)
                            KeepassGoogleDriveConnectionState.Failed -> stringResource(R.string.keepass_webdav_status_failed)
                        },
                        color = when (connectionState) {
                            KeepassGoogleDriveConnectionState.Connected -> MaterialTheme.colorScheme.primary
                            KeepassGoogleDriveConnectionState.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        stringResource(R.string.keepass_gdrive_current_path, currentPathLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            browserError?.let { error ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AnimatedVisibility(visible = session != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { loadDirectory(currentPath, currentFolderId) },
                            enabled = !isLoadingEntries,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                        OutlinedButton(
                            onClick = {
                                loadDirectory(
                                    GoogleDriveKeePassFileSource.parentPathOf(currentPath),
                                    null
                                )
                            },
                            enabled = !isLoadingEntries,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_webdav_go_parent))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCreateFolderDialog = true },
                            enabled = !isLoadingEntries,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_webdav_create_folder_confirm))
                        }
                        Button(
                            onClick = { showCreateDatabaseDialog = true },
                            enabled = !isLoadingEntries,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_gdrive_create_confirm))
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (isLoadingEntries) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.keepass_remote_loading_files))
                                }
                            } else if (entries.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.keepass_gdrive_empty_directory),
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                entries.forEach { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (entry.isDirectory) {
                                                    loadDirectory(entry.path, entry.id)
                                                } else {
                                                    selectedDatabaseEntry = entry
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Link,
                                            contentDescription = null
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(entry.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                if (entry.isDirectory) "/${entry.path}" else entry.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog && session != null) {
        CreateGoogleDriveFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { folderName ->
                coroutineScope.launch {
                    val result = viewModel.createGoogleDriveFolder(
                        accountId = session!!.accountId,
                        currentPath = currentPath,
                        currentFolderId = currentFolderId,
                        folderName = folderName
                    )
                    result.onSuccess { listing ->
                        currentPath = listing.currentPath
                        currentFolderId = listing.currentFolderId
                        entries = listing.entries
                        showCreateFolderDialog = false
                        browserError = null
                    }.onFailure { error ->
                        browserError = error.message ?: context.getString(R.string.keepass_webdav_create_folder_failed)
                    }
                }
            }
        )
    }

    selectedDatabaseEntry?.let { entry ->
        if (!entry.isDirectory && session != null) {
            AttachExistingGoogleDriveDatabaseDialog(
                entry = entry,
                onDismiss = { selectedDatabaseEntry = null },
                onAttach = { displayName, databasePassword, keyFileUri, description ->
                    val fileId = entry.id ?: run {
                        browserError = "Google Drive 文件标识为空"
                        selectedDatabaseEntry = null
                        return@AttachExistingGoogleDriveDatabaseDialog
                    }
                    viewModel.addGoogleDriveDatabase(
                        name = displayName,
                        accountId = session!!.accountId,
                        accountLabel = session!!.displayName,
                        remotePath = entry.path,
                        fileId = fileId,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description
                    )
                    selectedDatabaseEntry = null
                    onDismiss()
                }
            )
        }
    }

    if (showCreateDatabaseDialog && session != null) {
        CreateGoogleDriveDatabaseDialog(
            currentPath = currentPath,
            onDismiss = { showCreateDatabaseDialog = false },
            onGenerateKeyFile = { uri ->
                viewModel.generateKeyFile(uri)
            },
            onCreate = { name, password, keyFileUri, description ->
                viewModel.createGoogleDriveDatabase(
                    directoryPath = currentPath,
                    folderId = currentFolderId,
                    name = name,
                    accountId = session!!.accountId,
                    accountLabel = session!!.displayName,
                    databasePassword = password,
                    keyFileUri = keyFileUri,
                    creationOptions = KeePassDatabaseCreationOptions.remoteCompatibilityDefaults(),
                    description = description
                )
                showCreateDatabaseDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun CreateGoogleDriveFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_gdrive_create_folder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.keepass_gdrive_create_folder_message))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.keepass_webdav_create_folder_action)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(folderName.trim()) }, enabled = folderName.isNotBlank()) {
                Text(stringResource(R.string.keepass_webdav_create_folder_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AttachExistingGoogleDriveDatabaseDialog(
    entry: FileSourceEntry,
    onDismiss: () -> Unit,
    onAttach: (String, String, Uri?, String?) -> Unit
) {
    var displayName by remember { mutableStateOf(entry.name.removeSuffix(".kdbx")) }
    var databasePassword by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    val keyFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_gdrive_attach_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.database_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = databasePassword,
                    onValueChange = { databasePassword = it },
                    label = { Text(stringResource(R.string.keepass_webdav_database_password)) },
                    placeholder = { Text(stringResource(R.string.keepass_webdav_database_password_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
                OutlinedTextField(
                    value = keyFileName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.local_keepass_key_file_optional)) },
                    placeholder = { Text(stringResource(R.string.local_keepass_key_file_tap_to_select)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                    trailingIcon = {
                        IconButton(onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                        }
                    }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description_optional)) },
                    placeholder = { Text(stringResource(R.string.description_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAttach(
                        displayName.trim(),
                        databasePassword,
                        keyFileUri,
                        description.takeIf { it.isNotBlank() }
                    )
                },
                enabled = displayName.isNotBlank() && (databasePassword.isNotBlank() || keyFileUri != null)
            ) {
                Text(stringResource(R.string.keepass_gdrive_confirm_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CreateGoogleDriveDatabaseDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onGenerateKeyFile: (Uri) -> Unit,
    onCreate: (name: String, password: String, keyFileUri: Uri?, description: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }

    val keyFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }
    val createKeyFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri: Uri? ->
        uri?.let {
            onGenerateKeyFile(it)
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "new_keyfile.xml"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_gdrive_create_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(
                        R.string.keepass_webdav_create_target_path,
                        if (currentPath.isBlank()) stringResource(R.string.keepass_webdav_root_path) else "/$currentPath"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.keepass_webdav_create_name_label)) },
                    placeholder = { Text(stringResource(R.string.keepass_webdav_create_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.confirm_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions()
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { useKeyFile = !useKeyFile }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_keepass_use_key_file))
                            Text(
                                stringResource(R.string.local_keepass_use_key_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = useKeyFile, onCheckedChange = { useKeyFile = it })
                    }
                }
                AnimatedVisibility(visible = useKeyFile) {
                    OutlinedTextField(
                        value = keyFileName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.local_keepass_key_file)) },
                        placeholder = { Text(stringResource(R.string.local_keepass_key_file_pick_or_generate)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { createKeyFileLauncher.launch("monica.key") }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                                IconButton(onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                }
                            }
                        }
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description_optional)) },
                    placeholder = { Text(stringResource(R.string.description_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.trim(),
                        password,
                        if (useKeyFile) keyFileUri else null,
                        description.takeIf { it.isNotBlank() }
                    )
                },
                enabled = name.isNotBlank() &&
                    ((password.isNotBlank() && password == confirmPassword) || (useKeyFile && keyFileUri != null))
            ) {
                Text(stringResource(R.string.keepass_gdrive_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

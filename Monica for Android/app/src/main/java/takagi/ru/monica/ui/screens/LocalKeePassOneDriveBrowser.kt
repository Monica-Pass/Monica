package takagi.ru.monica.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.OneDriveAccountSession
import takagi.ru.monica.utils.OneDriveAuthManager
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.toOneDriveUserMessage
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

private enum class KeepassOneDriveConnectionState {
    NotConnected,
    Connecting,
    Connected,
    Failed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepassOneDriveBrowserBottomSheet(
    viewModel: LocalKeePassViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { OneDriveAuthManager(context) }

    var session by remember { mutableStateOf<OneDriveAccountSession?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileSourceEntry>>(emptyList()) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isLoadingEntries by remember { mutableStateOf(false) }
    var selectedDatabaseEntry by remember { mutableStateOf<FileSourceEntry?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateDatabaseDialog by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf(KeepassOneDriveConnectionState.NotConnected) }

    fun loadDirectory(targetPath: String = currentPath) {
        val activeSession = session ?: return
        coroutineScope.launch {
            isLoadingEntries = true
            browserError = null
            val result = viewModel.listOneDriveDirectory(
                accountId = activeSession.accountId,
                currentPath = targetPath
            )
            result.fold(
                onSuccess = { listing ->
                    currentPath = listing.currentPath
                    entries = listing.entries
                    connectionState = KeepassOneDriveConnectionState.Connected
                },
                onFailure = { error ->
                    browserError = error.toOneDriveUserMessage(context.getString(R.string.keepass_onedrive_load_files_failed))
                    connectionState = KeepassOneDriveConnectionState.Failed
                }
            )
            isLoadingEntries = false
            isConnecting = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { authManager.getCachedSession() }
            .getOrNull()
            ?.let { cached ->
                session = cached
                currentPath = ""
                loadDirectory("")
            }
    }

    LaunchedEffect(session?.accountId) {
        selectedDatabaseEntry = null
        showCreateFolderDialog = false
        showCreateDatabaseDialog = false
    }

    fun signInOrSwitchAccount() {
        if (activity == null) {
            browserError = context.getString(R.string.keepass_onedrive_activity_missing)
            connectionState = KeepassOneDriveConnectionState.Failed
            return
        }
        connectionState = KeepassOneDriveConnectionState.Connecting
        isConnecting = true
        browserError = null
        coroutineScope.launch {
            runCatching { authManager.signIn(activity) }
                .onSuccess { result ->
                    session = result
                    currentPath = ""
                    loadDirectory("")
                }
                .onFailure { error ->
                    isConnecting = false
                    connectionState = KeepassOneDriveConnectionState.Failed
                    browserError = error.toOneDriveUserMessage(
                        context.getString(R.string.keepass_onedrive_sign_in_failed)
                    )
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                stringResource(R.string.keepass_onedrive_attach_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                stringResource(R.string.keepass_onedrive_browser_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OneDriveLocationPanel(
                session = session,
                isConnecting = isConnecting,
                accountActionLabel = if (session == null) {
                    stringResource(R.string.keepass_onedrive_sign_in_action)
                } else {
                    stringResource(R.string.keepass_onedrive_switch_account)
                },
                connectionLabel = when (connectionState) {
                    KeepassOneDriveConnectionState.NotConnected -> stringResource(R.string.keepass_webdav_status_not_connected)
                    KeepassOneDriveConnectionState.Connecting -> stringResource(R.string.keepass_webdav_status_connecting)
                    KeepassOneDriveConnectionState.Connected -> stringResource(R.string.keepass_webdav_status_connected)
                    KeepassOneDriveConnectionState.Failed -> stringResource(R.string.keepass_webdav_status_failed)
                },
                connectionFailed = connectionState == KeepassOneDriveConnectionState.Failed,
                errorMessage = browserError,
                onAccountAction = ::signInOrSwitchAccount,
                browserTitle = stringResource(R.string.v2_select_database),
                currentPath = currentPath,
                isLoadingEntries = isLoadingEntries,
                entries = entries,
                emptyMessage = stringResource(R.string.keepass_onedrive_empty_directory),
                onNavigateUp = {
                    loadDirectory(OneDriveKeePassFileSource.parentPathOf(currentPath))
                },
                onRefresh = { loadDirectory(currentPath) },
                onCreateFolder = { showCreateFolderDialog = true },
                entryIcon = { entry ->
                    if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Link
                },
                entrySupportingText = { entry -> entry.path.toOneDriveDisplayPath() },
                onEntryClick = { entry ->
                    if (entry.isDirectory) loadDirectory(entry.path)
                    else selectedDatabaseEntry = entry
                }
            ) {
                Button(
                    onClick = { showCreateDatabaseDialog = true },
                    enabled = session != null && !isLoadingEntries,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.keepass_webdav_create_confirm))
                }
            }
        }
    }

    if (showCreateFolderDialog && session != null) {
        CreateOneDriveFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { folderName ->
                coroutineScope.launch {
                    val result = viewModel.createOneDriveFolder(
                        accountId = session!!.accountId,
                        currentPath = currentPath,
                        folderName = folderName
                    )
                    result.fold(
                        onSuccess = { listing ->
                            currentPath = listing.currentPath
                            entries = listing.entries
                            showCreateFolderDialog = false
                        },
                        onFailure = { error ->
                            browserError = error.toOneDriveUserMessage(context.getString(R.string.keepass_webdav_create_folder_failed))
                        }
                    )
                }
            }
        )
    }

    selectedDatabaseEntry?.let { entry ->
        val activeSession = session
        if (activeSession != null) {
            AttachExistingOneDriveDatabaseDialog(
                entry = entry,
                onDismiss = { selectedDatabaseEntry = null },
                onAttach = { displayName, databasePassword, keyFileUri, description, keepKeyFileCopy ->
                    viewModel.addOneDriveDatabase(
                        name = displayName,
                        accountId = activeSession.accountId,
                        accountLabel = activeSession.username.ifBlank { activeSession.displayName },
                        remotePath = entry.path,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                    selectedDatabaseEntry = null
                    onDismiss()
                }
            )
        }
    }

    if (showCreateDatabaseDialog) {
        val activeSession = session
        if (activeSession != null) {
            CreateOneDriveDatabaseDialog(
                currentPath = currentPath,
                onDismiss = { showCreateDatabaseDialog = false },
                onGenerateKeyFile = { uri -> viewModel.generateKeyFile(uri) },
                onCreate = { name, password, keyFileUri, options, description, keepKeyFileCopy ->
                    viewModel.createOneDriveDatabase(
                        directoryPath = currentPath,
                        name = name,
                        accountId = activeSession.accountId,
                        accountLabel = activeSession.username.ifBlank { activeSession.displayName },
                        databasePassword = password,
                        keyFileUri = keyFileUri,
                        creationOptions = options,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                    showCreateDatabaseDialog = false
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun CreateOneDriveFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_onedrive_create_folder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.keepass_onedrive_create_folder_message))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name_label)) },
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun AttachExistingOneDriveDatabaseDialog(
    entry: FileSourceEntry,
    onDismiss: () -> Unit,
    onAttach: (displayName: String, databasePassword: String, keyFileUri: Uri?, description: String?, keepKeyFileCopy: Boolean) -> Unit
) {
    var displayName by remember(entry.path) { mutableStateOf(entry.name.removeSuffix(".kdbx")) }
    var databasePassword by remember(entry.path) { mutableStateOf("") }
    var showDatabasePassword by remember(entry.path) { mutableStateOf(false) }
    var description by remember(entry.path) { mutableStateOf("") }
    var keyFileUri by remember(entry.path) { mutableStateOf<Uri?>(null) }
    var keyFileName by remember(entry.path) { mutableStateOf("") }
    var keepKeyFileCopy by remember(entry.path) { mutableStateOf(false) }

    val keyFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_onedrive_attach_title)) },
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
                    visualTransformation = if (showDatabasePassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    trailingIcon = {
                        IconButton(onClick = { showDatabasePassword = !showDatabasePassword }) {
                            Icon(
                                if (showDatabasePassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
                if (keyFileUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keepKeyFileCopy = !keepKeyFileCopy },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = keepKeyFileCopy,
                            onCheckedChange = { keepKeyFileCopy = it },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_keepass_keep_key_file_copy))
                            Text(
                                stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                    onAttach(
                        displayName.trim(),
                        databasePassword,
                        keyFileUri,
                        description.takeIf { it.isNotBlank() },
                        keyFileUri != null && keepKeyFileCopy,
                    )
                },
                enabled = displayName.isNotBlank() && (databasePassword.isNotBlank() || keyFileUri != null)
            ) {
                Text(stringResource(R.string.keepass_onedrive_confirm_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CreateOneDriveDatabaseDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onGenerateKeyFile: (Uri) -> Unit,
    onCreate: (
        name: String,
        password: String,
        keyFileUri: Uri?,
        options: KeePassDatabaseCreationOptions,
        description: String?,
        keepKeyFileCopy: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepKeyFileCopy by remember { mutableStateOf(false) }
    val defaultOptions = remember { KeePassDatabaseCreationOptions.remoteCompatibilityDefaults() }
    var formatVersion by remember { mutableStateOf(defaultOptions.formatVersion) }
    var cipherAlgorithm by remember { mutableStateOf(defaultOptions.cipherAlgorithm) }
    var kdfAlgorithm by remember { mutableStateOf(defaultOptions.kdfAlgorithm) }
    var transformRounds by remember { mutableStateOf(defaultOptions.transformRounds.toString()) }
    var memoryMb by remember {
        mutableStateOf((defaultOptions.memoryBytes / 1024L / 1024L).toString())
    }
    var parallelism by remember { mutableStateOf(defaultOptions.parallelism.toString()) }
    var showAdvancedCryptoOptions by remember { mutableStateOf(false) }

    fun setUseKeyFile(enabled: Boolean) {
        useKeyFile = enabled
        if (!enabled) keepKeyFileCopy = false
    }

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

    val availableCipherOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassCipherAlgorithm.AES, KeePassCipherAlgorithm.TWOFISH)
        } else {
            listOf(KeePassCipherAlgorithm.AES, KeePassCipherAlgorithm.CHACHA20, KeePassCipherAlgorithm.TWOFISH)
        }
    }
    val availableKdfOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassKdfAlgorithm.AES_KDF)
        } else {
            listOf(KeePassKdfAlgorithm.ARGON2D, KeePassKdfAlgorithm.ARGON2ID, KeePassKdfAlgorithm.AES_KDF)
        }
    }

    LaunchedEffect(formatVersion) {
        if (cipherAlgorithm !in availableCipherOptions) cipherAlgorithm = availableCipherOptions.first()
        if (kdfAlgorithm !in availableKdfOptions) {
            val previousDefaultRounds = KeePassDatabaseCreationOptions
                .defaultTransformRoundsFor(kdfAlgorithm)
                .toString()
            val nextKdfAlgorithm = availableKdfOptions.first()
            val shouldUseNextDefaultRounds = transformRounds.isBlank() || transformRounds == previousDefaultRounds
            kdfAlgorithm = nextKdfAlgorithm
            if (shouldUseNextDefaultRounds) {
                transformRounds = KeePassDatabaseCreationOptions
                    .defaultTransformRoundsFor(nextKdfAlgorithm)
                    .toString()
            }
        }
    }

    val roundsValue = transformRounds.toLongOrNull()
    val memoryMbValue = memoryMb.toLongOrNull()
    val parallelismValue = parallelism.toIntOrNull()
    val advancedOptionsValid = roundsValue != null && roundsValue > 0L &&
        (
            kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF ||
                ((memoryMbValue != null && memoryMbValue > 0L) && (parallelismValue != null && parallelismValue > 0))
            )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_onedrive_create_title)) },
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
                    onClick = { setUseKeyFile(!useKeyFile) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_keepass_use_key_file))
                            Text(
                                stringResource(R.string.local_keepass_use_key_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = useKeyFile, onCheckedChange = ::setUseKeyFile)
                    }
                }
                AnimatedVisibility(visible = useKeyFile) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        if (keyFileUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { keepKeyFileCopy = !keepKeyFileCopy },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = keepKeyFileCopy,
                                    onCheckedChange = { keepKeyFileCopy = it },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.local_keepass_keep_key_file_copy))
                                    Text(
                                        stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { showAdvancedCryptoOptions = !showAdvancedCryptoOptions }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.local_keepass_advanced_crypto_options),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (showAdvancedCryptoOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                }
                AnimatedVisibility(visible = showAdvancedCryptoOptions) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeepassOneDriveOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdbx_version),
                            selectedText = if (formatVersion == KeePassFormatVersion.KDBX3) {
                                stringResource(R.string.local_keepass_kdbx3)
                            } else {
                                stringResource(R.string.local_keepass_kdbx4)
                            },
                            options = KeePassFormatVersion.entries,
                            optionLabel = {
                                if (it == KeePassFormatVersion.KDBX3) stringResource(R.string.local_keepass_kdbx3)
                                else stringResource(R.string.local_keepass_kdbx4)
                            },
                            onSelected = { formatVersion = it }
                        )
                        KeepassOneDriveOptionDropdown(
                            label = stringResource(R.string.local_keepass_cipher_algorithm),
                            selectedText = keepassOneDriveCipherLabel(cipherAlgorithm),
                            options = availableCipherOptions,
                            optionLabel = { keepassOneDriveCipherLabel(it) },
                            onSelected = { cipherAlgorithm = it }
                        )
                        KeepassOneDriveOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdf_algorithm),
                            selectedText = keepassOneDriveKdfLabel(kdfAlgorithm),
                            options = availableKdfOptions,
                            optionLabel = { keepassOneDriveKdfLabel(it) },
                            onSelected = {
                                val previousDefaultRounds = KeePassDatabaseCreationOptions
                                    .defaultTransformRoundsFor(kdfAlgorithm)
                                    .toString()
                                val shouldUseNextDefaultRounds = transformRounds.isBlank() ||
                                    transformRounds == previousDefaultRounds
                                kdfAlgorithm = it
                                if (shouldUseNextDefaultRounds) {
                                    transformRounds = KeePassDatabaseCreationOptions
                                        .defaultTransformRoundsFor(it)
                                        .toString()
                                }
                            }
                        )
                        OutlinedTextField(
                            value = transformRounds,
                            onValueChange = { transformRounds = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.local_keepass_transform_rounds)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        AnimatedVisibility(visible = kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = memoryMb,
                                    onValueChange = { memoryMb = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_memory_mb)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = parallelism,
                                    onValueChange = { parallelism = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_parallelism)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
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
                    val options = KeePassDatabaseCreationOptions(
                        formatVersion = formatVersion,
                        cipherAlgorithm = cipherAlgorithm,
                        kdfAlgorithm = kdfAlgorithm,
                        transformRounds = roundsValue ?: KeePassDatabaseCreationOptions.defaultTransformRoundsFor(kdfAlgorithm),
                        memoryBytes = ((memoryMbValue ?: (defaultOptions.memoryBytes / 1024L / 1024L)) * 1024L * 1024L),
                        parallelism = parallelismValue ?: defaultOptions.parallelism
                    ).normalized()
                    onCreate(
                        name.trim(),
                        password,
                        if (useKeyFile) keyFileUri else null,
                        options,
                        description.takeIf { it.isNotBlank() },
                        useKeyFile && keepKeyFileCopy,
                    )
                },
                enabled = name.isNotBlank() &&
                    ((password.isNotBlank() && password == confirmPassword) || (useKeyFile && keyFileUri != null)) &&
                    advancedOptionsValid
            ) {
                Text(stringResource(R.string.keepass_onedrive_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> KeepassOneDriveOptionDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun keepassOneDriveCipherLabel(algorithm: KeePassCipherAlgorithm): String {
    return when (algorithm) {
        KeePassCipherAlgorithm.AES -> stringResource(R.string.local_keepass_cipher_aes)
        KeePassCipherAlgorithm.CHACHA20 -> stringResource(R.string.local_keepass_cipher_chacha20)
        KeePassCipherAlgorithm.TWOFISH -> stringResource(R.string.local_keepass_cipher_twofish)
    }
}

@Composable
private fun keepassOneDriveKdfLabel(algorithm: KeePassKdfAlgorithm): String {
    return when (algorithm) {
        KeePassKdfAlgorithm.AES_KDF -> stringResource(R.string.local_keepass_kdf_aes)
        KeePassKdfAlgorithm.ARGON2D -> stringResource(R.string.local_keepass_kdf_argon2d)
        KeePassKdfAlgorithm.ARGON2ID -> stringResource(R.string.local_keepass_kdf_argon2id)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

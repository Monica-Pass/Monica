package takagi.ru.monica.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.utils.WebDavKeePassFileSource
import takagi.ru.monica.webdav.WebDavUntrustedCertificateException
import takagi.ru.monica.viewmodel.MdbxKeyFileSelection
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.text.Normalizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdbxWebDavOpenScreen(
    viewModel: MdbxViewModel,
    onNavigateBack: () -> Unit
) {
    val strings = rememberScreenStrings()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val operationState by viewModel.operationState.collectAsState()

    val webDavHelper = remember { WebDavHelper(context) }
    val savedConfig = remember { webDavHelper.getCurrentConfig() }

    var serverUrl by remember { mutableStateOf(savedConfig?.serverUrl ?: "") }
    var username by remember { mutableStateOf(savedConfig?.username ?: "") }
    var webDavPassword by remember { mutableStateOf(webDavHelper.getCurrentPasswordForEdit()) }
    var showWebDavPassword by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.NotTested) }
    var pendingCertificate by remember { mutableStateOf<WebDavUntrustedCertificateException?>(null) }

    var webDavCurrentPath by remember { mutableStateOf("") }
    var webDavEntries by remember { mutableStateOf<List<FileSourceEntry>>(emptyList()) }
    var webDavIsLoadingEntries by remember { mutableStateOf(false) }
    var selectedWebDavFile by remember { mutableStateOf<FileSourceEntry?>(null) }

    var masterPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var unlockMethod by remember { mutableStateOf(MdbxUnlockMethod.MASTER_PASSWORD) }
    var keyFile by remember { mutableStateOf<MdbxKeyFileSelection?>(null) }
    var keyFileError by remember { mutableStateOf<String?>(null) }
    var selectedEngine by remember { mutableStateOf(MdbxEngineType.KOTLIN_MDBX1) }
    var submitted by remember { mutableStateOf(false) }

    val passwordRequired = selectedEngine == MdbxEngineType.RUST_MDBX2 ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
    val keyFileRequired = selectedEngine == MdbxEngineType.KOTLIN_MDBX1 &&
        (unlockMethod == MdbxUnlockMethod.KEY_FILE ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
        )

    val normalizedMasterPassword = remember(masterPassword) {
        Normalizer.normalize(masterPassword, Normalizer.Form.NFC)
    }
    val normalizedConfirmPassword = remember(confirmPassword) {
        Normalizer.normalize(confirmPassword, Normalizer.Form.NFC)
    }

    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                keyFileError = null
                viewModel.readSelectedKeyFile(uri)
                    .onSuccess { keyFile = it }
                    .onFailure { keyFileError = it.message ?: strings.get(R.string.mdbx_ui_key_file_read_error) }
            }
        }
    }

    val keyFileCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                keyFileError = null
                viewModel.writeGeneratedKeyFile(uri)
                    .onSuccess { keyFile = it }
                    .onFailure { keyFileError = it.message ?: strings.get(R.string.mdbx_ui_key_file_generate_error) }
            }
        }
    }

    val loadWebDavDirectory: (String?) -> Unit = { path ->
        scope.launch {
            webDavIsLoadingEntries = true
            val result = viewModel.listWebDavDirectory(
                serverUrl = serverUrl,
                username = username,
                password = webDavPassword,
                path = path
            )
            result.onSuccess { entries ->
                webDavEntries = entries
                webDavCurrentPath = path ?: ""
            }.onFailure {
                webDavEntries = emptyList()
            }
            webDavIsLoadingEntries = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearOperationState()
    }
    LaunchedEffect(selectedEngine) {
        if (selectedEngine == MdbxEngineType.RUST_MDBX2) {
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD
            keyFile = null
        }
    }
    LaunchedEffect(operationState, submitted) {
        if (submitted && operationState is MdbxViewModel.OperationState.Success) {
            submitted = false
            viewModel.clearOperationState()
            onNavigateBack()
        }
    }

    fun testConnection() {
        connectionState = ConnectionState.Testing
        scope.launch {
            val result = viewModel.testWebDavConnection(serverUrl, username, webDavPassword)
            val error = result.exceptionOrNull()
            val certificateError = error?.findWebDavCertificateError()
            if (certificateError != null) {
                pendingCertificate = certificateError
                connectionState = ConnectionState.NotTested
                return@launch
            }
            connectionState = if (result.isSuccess) ConnectionState.Connected
            else ConnectionState.Failed(error?.message ?: "Unknown error")
            if (result.isSuccess) webDavHelper.configure(serverUrl, username, webDavPassword)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mdbx_connect_to_remote_vault)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === Card: WebDAV Connection ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        strings.get(R.string.mdbx_ui_webdav_connection),
                        style = MaterialTheme.typography.titleMedium
                    )
                    MdbxWebDavConnectionSection(
                        serverUrl = serverUrl,
                        onServerUrlChange = {
                            serverUrl = it
                            connectionState = ConnectionState.NotTested
                            selectedWebDavFile = null
                            webDavEntries = emptyList()
                        },
                        username = username,
                        onUsernameChange = {
                            username = it
                            connectionState = ConnectionState.NotTested
                            selectedWebDavFile = null
                            webDavEntries = emptyList()
                        },
                        password = webDavPassword,
                        onPasswordChange = {
                            webDavPassword = it
                            connectionState = ConnectionState.NotTested
                            selectedWebDavFile = null
                            webDavEntries = emptyList()
                        },
                        showPassword = showWebDavPassword,
                        onTogglePasswordVisibility = { showWebDavPassword = !showWebDavPassword },
                        connectionState = connectionState,
                        onTestConnection = ::testConnection
                    )
                }
            }

            // === Card: Browse Files (shown when connected) ===
            AnimatedVisibility(
                visible = connectionState is ConnectionState.Connected,
                enter = expandVertically() + fadeIn()
            ) {
                LaunchedEffect(connectionState) {
                    if (webDavEntries.isEmpty()) {
                        loadWebDavDirectory("")
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.mdbx_select_remote_file),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row {
                                IconButton(
                                    onClick = {
                                        val parent = WebDavKeePassFileSource.parentPathOf(webDavCurrentPath)
                                        loadWebDavDirectory(parent)
                                        selectedWebDavFile = null
                                    },
                                    enabled = webDavCurrentPath.isNotBlank()
                                ) {
                                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.mdbx_webdav_parent_dir))
                                }
                                IconButton(onClick = { loadWebDavDirectory(webDavCurrentPath) }) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.mdbx_webdav_refresh))
                                }
                            }
                        }

                        if (webDavCurrentPath.isNotBlank()) {
                            Text(
                                "/$webDavCurrentPath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            if (webDavIsLoadingEntries) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else if (webDavEntries.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(R.string.mdbx_no_mdbx_files),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                    items(webDavEntries, key = { it.path }) { entry ->
                                        val isMdbxFile = !entry.isDirectory &&
                                            entry.name.endsWith(".mdbx", ignoreCase = true)
                                        val isSelected = selectedWebDavFile?.path == entry.path

                                        ListItem(
                                            headlineContent = {
                                                Text(
                                                    entry.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = when {
                                                        isSelected -> MaterialTheme.colorScheme.primary
                                                        entry.isDirectory || isMdbxFile -> MaterialTheme.colorScheme.onSurface
                                                        else -> MaterialTheme.colorScheme.outlineVariant
                                                    }
                                                )
                                            },
                                            leadingContent = {
                                                Icon(
                                                    if (entry.isDirectory) Icons.Default.Folder
                                                    else Icons.Default.Key,
                                                    contentDescription = null,
                                                    tint = when {
                                                        isSelected -> MaterialTheme.colorScheme.primary
                                                        entry.isDirectory -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        isMdbxFile -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        else -> MaterialTheme.colorScheme.outlineVariant
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            trailingContent = {
                                                when {
                                                    entry.isDirectory -> Icon(
                                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    isSelected -> Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            colors = ListItemDefaults.colors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                            modifier = Modifier.clickable(
                                                enabled = entry.isDirectory || isMdbxFile
                                            ) {
                                                if (entry.isDirectory) {
                                                    loadWebDavDirectory(entry.path)
                                                    selectedWebDavFile = null
                                                } else if (isMdbxFile) {
                                                    selectedWebDavFile = entry
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        selectedWebDavFile?.let { file ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "${stringResource(R.string.mdbx_webdav_selected_file)}: ${file.name}",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        null,
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }
                }
            }

            // === Vault settings (only after file selected) ===
            AnimatedVisibility(
                visible = selectedWebDavFile != null,
                enter = expandVertically() + fadeIn()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MdbxEngineTypeSection(
                        selectedEngine = selectedEngine,
                        onEngineChange = { selectedEngine = it },
                        remote = true
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.mdbx_unlock_existing_vault),
                                style = MaterialTheme.typography.titleMedium
                            )
                            MdbxPasswordFieldSection(
                                masterPassword = masterPassword,
                                onMasterPasswordChange = { masterPassword = it },
                                confirmPassword = confirmPassword,
                                onConfirmPasswordChange = { confirmPassword = it },
                                passwordRequired = passwordRequired
                            )
                            if (selectedEngine == MdbxEngineType.KOTLIN_MDBX1) {
                                MdbxUnlockMethodSection(
                                    unlockMethod = unlockMethod,
                                    onUnlockMethodChange = { unlockMethod = it },
                                    embedded = true
                                )
                                MdbxKeyFileSection(
                                    keyFile = keyFile,
                                    keyFileError = keyFileError,
                                    keyFileRequired = keyFileRequired,
                                    onPickKeyFile = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                                    onGenerateKeyFile = { keyFileCreateLauncher.launch("monica-mdbx.key") },
                                    embedded = true
                                )
                            }
                        }
                    }
                }
            }

            // === Submit Button ===
            val isFormValid = connectionState is ConnectionState.Connected &&
                selectedWebDavFile != null &&
                serverUrl.isNotBlank() &&
                username.isNotBlank() &&
                webDavPassword.isNotBlank() &&
                (!passwordRequired || (
                    normalizedMasterPassword.isNotBlank() &&
                        normalizedMasterPassword == normalizedConfirmPassword
                    )) &&
                (!keyFileRequired || keyFile != null) &&
                operationState !is MdbxViewModel.OperationState.Loading

            Button(
                onClick = {
                    selectedWebDavFile?.let { file ->
                        submitted = true
                        viewModel.connectToExistingWebDavVault(
                            masterPassword = masterPassword,
                            unlockMethod = unlockMethod,
                            keyFile = keyFile,
                            tigaMode = MdbxTigaMode.MULTI,
                            serverUrl = serverUrl,
                            username = username,
                            webDavPassword = webDavPassword,
                            remoteFilePath = file.path,
                            description = null,
                            engineType = selectedEngine
                        )
                    }
                },
                enabled = isFormValid,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (operationState is MdbxViewModel.OperationState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mdbx_creating_vault))
                } else {
                    Text(stringResource(R.string.mdbx_connect_to_remote_vault))
                }
            }

            MdbxOperationFeedback(operationState)
        }
    }
    pendingCertificate?.let { certificate ->
        WebDavCertificateDialog(
            certificate = certificate,
            onDismiss = { pendingCertificate = null },
            onContinue = {
                pendingCertificate = null
                testConnection()
            },
        )
    }
}

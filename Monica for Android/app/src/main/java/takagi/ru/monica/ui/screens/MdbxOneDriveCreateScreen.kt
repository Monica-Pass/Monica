package takagi.ru.monica.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
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
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.OneDriveAccountSession
import takagi.ru.monica.utils.OneDriveAuthManager
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.toOneDriveUserMessage
import takagi.ru.monica.viewmodel.MdbxKeyFileSelection
import takagi.ru.monica.viewmodel.MdbxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdbxOneDriveCreateScreen(
    viewModel: MdbxViewModel,
    onNavigateBack: () -> Unit
) {
    val strings = rememberScreenStrings()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val operationState by viewModel.operationState.collectAsState()

    val authManager = remember { OneDriveAuthManager(context) }
    var session by remember { mutableStateOf<OneDriveAccountSession?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileSourceEntry>>(emptyList()) }
    var isLoadingEntries by remember { mutableStateOf(false) }

    var vaultName by remember { mutableStateOf("") }
    var masterPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var unlockMethod by remember { mutableStateOf(MdbxUnlockMethod.MASTER_PASSWORD) }
    var keyFile by remember { mutableStateOf<MdbxKeyFileSelection?>(null) }
    var keyFileError by remember { mutableStateOf<String?>(null) }
    var selectedTigaMode by remember { mutableStateOf(MdbxTigaMode.MULTI) }
    var selectedEngine by remember { mutableStateOf(MdbxEngineType.RUST_MDBX2) }
    var submitted by remember { mutableStateOf(false) }

    val passwordRequired = selectedEngine == MdbxEngineType.RUST_MDBX2 ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
    val keyFileRequired = selectedEngine == MdbxEngineType.KOTLIN_MDBX1 &&
        (unlockMethod == MdbxUnlockMethod.KEY_FILE ||
        unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
        )

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

    fun loadDirectory(targetPath: String) {
        val activeSession = session ?: return
        scope.launch {
            isLoadingEntries = true
            viewModel.listOneDriveMdbxDirectory(
                accountId = activeSession.accountId,
                currentPath = targetPath
            ).fold(
                onSuccess = { listing ->
                    authError = null
                    currentPath = listing.currentPath
                    entries = listing.entries
                },
                onFailure = { error ->
                    authError = error.toOneDriveUserMessage(strings.get(R.string.mdbx_ui_onedrive_folder_error))
                }
            )
            isLoadingEntries = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearOperationState()
        runCatching { authManager.getCachedSession() }
            .getOrNull()
            ?.let { cached ->
                session = cached
                loadDirectory("")
            }
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

    fun signInOrSwitchAccount() {
        if (activity == null) return
        isConnecting = true
        authError = null
        scope.launch {
            runCatching { authManager.signIn(activity) }
                .onSuccess { activeSession ->
                    session = activeSession
                    loadDirectory("")
                }
                .onFailure { error ->
                    authError = error.toOneDriveUserMessage(strings.get(R.string.keepass_onedrive_sign_in_failed))
                }
            isConnecting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mdbx_create_vault_title)) },
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
            OneDriveLocationPanel(
                session = session,
                isConnecting = isConnecting,
                accountActionLabel = if (session == null) {
                    stringResource(R.string.keepass_onedrive_sign_in_action)
                } else {
                    stringResource(R.string.keepass_onedrive_switch_account)
                },
                connectionLabel = when {
                    isConnecting -> stringResource(R.string.keepass_webdav_status_connecting)
                    authError != null -> stringResource(R.string.keepass_webdav_status_failed)
                    session != null -> stringResource(R.string.keepass_webdav_status_connected)
                    else -> stringResource(R.string.keepass_webdav_status_not_connected)
                },
                connectionFailed = authError != null,
                errorMessage = authError,
                onAccountAction = ::signInOrSwitchAccount,
                browserTitle = stringResource(R.string.mdbx_select_directory),
                currentPath = currentPath,
                isLoadingEntries = isLoadingEntries,
                entries = entries.filter { it.isDirectory },
                emptyMessage = stringResource(R.string.onedrive_backup_no_folders),
                onNavigateUp = {
                    loadDirectory(OneDriveKeePassFileSource.parentPathOf(currentPath))
                },
                onRefresh = { loadDirectory(currentPath) },
                entrySupportingText = { entry -> entry.path.toOneDriveDisplayPath() },
                onEntryClick = { entry -> loadDirectory(entry.path) }
            ) {
                Text(
                    text = stringResource(
                        R.string.mdbx_create_location,
                        currentPath.toOneDriveDisplayPath()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === Vault settings (only after auth) ===
            AnimatedVisibility(
                visible = session != null,
                enter = expandVertically() + fadeIn()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MdbxEngineTypeSection(
                        selectedEngine = selectedEngine,
                        onEngineChange = { selectedEngine = it },
                        remote = true,
                        selectedTigaMode = selectedTigaMode,
                        onTigaModeChange = { selectedTigaMode = it }
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
                                stringResource(R.string.mdbx_vault_settings),
                                style = MaterialTheme.typography.titleMedium
                            )
                            MdbxVaultNameField(
                                vaultName = vaultName,
                                onVaultNameChange = { vaultName = it }
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
            val isFormValid = session != null &&
                vaultName.isNotBlank() &&
                (!passwordRequired || (
                    masterPassword.isNotBlank() &&
                        java.text.Normalizer.normalize(masterPassword, java.text.Normalizer.Form.NFC) ==
                            java.text.Normalizer.normalize(confirmPassword, java.text.Normalizer.Form.NFC)
                    )) &&
                (!keyFileRequired || keyFile != null) &&
                operationState !is MdbxViewModel.OperationState.Loading

            Button(
                onClick = {
                    session?.let { s ->
                        submitted = true
                        viewModel.createOneDriveVault(
                            name = vaultName,
                            masterPassword = masterPassword,
                            unlockMethod = unlockMethod,
                            keyFile = keyFile,
                            tigaMode = selectedTigaMode,
                            accountId = s.accountId,
                            accountLabel = s.displayName.ifBlank { s.username },
                            directoryPath = currentPath.ifBlank { null },
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
                    Text(strings.get(R.string.mdbx_ui_onedrive_create_vault))
                }
            }

            MdbxOperationFeedback(operationState)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package takagi.ru.monica.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.SteamMaFileExportCandidate
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.transfer.*
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.BiometricHelper

private class PendingDatabaseExport(
    val source: ImportDestination,
    val option: ExportOption,
    val preferences: BackupPreferences,
    val password: String?,
    val steamIds: Set<Long>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDataScreen(
    onNavigateBack: () -> Unit,
    onExportZip: suspend (Uri, BackupPreferences, String?, ImportDestination, TransferProgressReporter) -> Result<String>,
    onExportKdbx: suspend (Uri, String, ImportDestination, TransferProgressReporter) -> Result<String>,
    onLoadSteamMaFileCandidates: suspend (ImportDestination) -> Result<List<SteamMaFileExportCandidate>>,
    onExportSteamMaFile: suspend (Uri, Set<Long>, ImportDestination, TransferProgressReporter) -> Result<String>,
    biometricEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sourceKey by rememberSaveable { mutableStateOf(ImportDestination.Local.key) }
    val dataJob by DatabaseExportJobs.state.collectAsState()
    val job = dataJob?.takeIf { it.kind == DataTaskKind.EXPORT }
    LaunchedEffect(Unit) { DatabaseExportJobs.recoverInterrupted(context.applicationContext) }
    val source = ImportDestination.fromKey(job?.sourceKey ?: sourceKey)
    var formatKey by rememberSaveable { mutableStateOf(ExportOption.ZIP_BACKUP.name) }
    val option = ExportOption.valueOf(job?.formatKey ?: formatKey)
    LaunchedEffect(job?.id) {
        job?.let { sourceKey = it.sourceKey; formatKey = it.formatKey }
    }
    var preferences by remember { mutableStateOf(BackupPreferences()) }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var showFormats by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingDatabaseExport?>(null) }
    val exporting = job?.status == ExportJobStatus.RUNNING
    var steamCandidates by remember { mutableStateOf<List<SteamMaFileExportCandidate>>(emptyList()) }
    var selectedSteamIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loadingSteam by remember { mutableStateOf(false) }
    var showSteamRisk by remember { mutableStateOf(false) }
    var showSteamIdentity by remember { mutableStateOf(false) }
    var identityPassword by remember { mutableStateOf("") }
    var identityError by remember { mutableStateOf(false) }
    val security = remember { SecurityManager(context) }
    val biometric = remember { BiometricHelper(context) }

    fun loadSteam() {
        loadingSteam = true
        scope.launch {
            val requestedSource = source
            val result = onLoadSteamMaFileCandidates(requestedSource)
            if (ImportDestination.fromKey(sourceKey) == requestedSource) result.onSuccess {
                steamCandidates = it
                selectedSteamIds = it.map { account -> account.id }.toSet()
            }.onFailure { snackbar.showSnackbar(it.message ?: context.getString(R.string.export_data_error)) }
            loadingSteam = false
        }
    }
    LaunchedEffect(sourceKey, option) {
        steamCandidates = emptyList(); selectedSteamIds = emptySet()
        if (option == ExportOption.STEAM_MAFILE) loadSteam()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        pending = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && request != null && uri != null) {
            val started = DatabaseExportJobs.start(context.applicationContext, request.source.key, uri, request.option.name) { progress ->
                when (request.option) {
                    ExportOption.ZIP_BACKUP -> onExportZip(uri, request.preferences, request.password, request.source, progress)
                    ExportOption.KDBX -> onExportKdbx(uri, checkNotNull(request.password), request.source, progress)
                    ExportOption.STEAM_MAFILE -> onExportSteamMaFile(uri, request.steamIds, request.source, progress)
                }
            }
            if (!started) scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                    }
                }
                snackbar.showSnackbar(context.getString(R.string.transfer_background_start_failed))
            }
        }
    }
    fun chooseLocation(secret: String? = null) {
        val request = PendingDatabaseExport(source, option, preferences, secret, selectedSteamIds.toSet())
        pending = request
        var spec = exportDocumentSpec(option, encryptedZip = option == ExportOption.ZIP_BACKUP && secret != null)
        if (option == ExportOption.STEAM_MAFILE && selectedSteamIds.size == 1) {
            spec = ExportDocumentSpec("steam_${System.currentTimeMillis()}.maFile", "application/json")
        }
        try {
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = spec.mimeType
                putExtra(Intent.EXTRA_TITLE, spec.fileName)
            })
        } catch (error: Exception) {
            pending = null
            scope.launch { snackbar.showSnackbar(context.getString(R.string.error_launch_export, error.message.orEmpty())) }
        }
        password = ""; confirmation = ""
    }
    fun begin() {
        if (dataJob?.status == ExportJobStatus.RUNNING || pending != null) return
        if (option == ExportOption.STEAM_MAFILE) { showSteamRisk = true; return }
        if (option == ExportOption.KDBX || encrypt) {
            password = ""; confirmation = ""; passwordError = null; showPassword = true
        } else chooseLocation()
    }
    val formatTitle = when (option) {
        ExportOption.ZIP_BACKUP -> R.string.transfer_zip_title
        ExportOption.KDBX -> R.string.export_option_kdbx
        ExportOption.STEAM_MAFILE -> R.string.steam_mafile_export_title
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.export_data_title)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.go_back))
            } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface {
                Button(onClick = { if (exporting) onNavigateBack() else begin() },
                    enabled = exporting || (dataJob?.status != ExportJobStatus.RUNNING && pending == null && (option != ExportOption.ZIP_BACKUP || preferences.hasDatabaseExportContent()) &&
                        (option != ExportOption.STEAM_MAFILE || (!loadingSteam && selectedSteamIds.isNotEmpty()))),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(28.dp)) {
                    Icon(if (exporting) Icons.Default.ArrowBack else Icons.Default.Upload, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (exporting) R.string.transfer_background_continue else R.string.transfer_choose_location))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TransferDestinationField(source, { sourceKey = it.key; DatabaseExportJobs.dismiss() },
                enabled = !exporting && pending == null, forExport = true)
            TransferChoiceRow(stringResource(formatTitle), stringResource(when(option) {
                ExportOption.ZIP_BACKUP -> R.string.transfer_zip_description
                ExportOption.KDBX -> R.string.transfer_kdbx_description
                ExportOption.STEAM_MAFILE -> R.string.steam_mafile_export_desc
            }), if (option == ExportOption.ZIP_BACKUP) Icons.Default.Archive else Icons.Default.Description,
                enabled = !exporting && pending == null, onClick = { showFormats = true })
            if (exporting) TransferProgressCard(job!!.progress, background = true)
            else job?.let { state ->
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (state.status == ExportJobStatus.SUCCEEDED) R.string.transfer_export_done
                            else R.string.export_data_error), style = MaterialTheme.typography.titleLarge)
                        state.message?.let { Text(it) }
                        TextButton(onClick = DatabaseExportJobs::dismiss) { Text(stringResource(R.string.close)) }
                    }
                }
            }
            if (!exporting && option == ExportOption.ZIP_BACKUP) {
                Text(stringResource(R.string.transfer_contents), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ExportScopeToggle(stringResource(R.string.backup_content_passwords), preferences.includePasswords, 0, 7) {
                        preferences = preferences.copy(includePasswords = it)
                    }
                    ExportScopeToggle(stringResource(R.string.backup_content_passkeys), preferences.includePasskeys, 1, 7) {
                        preferences = preferences.copy(includePasskeys = it); if (it) encrypt = true
                    }
                    ExportScopeToggle(stringResource(R.string.backup_content_authenticators), preferences.includeAuthenticators, 2, 7) {
                        preferences = preferences.copy(includeAuthenticators = it)
                    }
                    ExportScopeToggle(stringResource(R.string.backup_content_wallet), preferences.includeBankCards || preferences.includeDocuments, 3, 7) {
                        preferences = preferences.copy(includeBankCards = it, includeDocuments = it)
                    }
                    ExportScopeToggle(stringResource(R.string.backup_content_notes), preferences.includeNotes, 4, 7) {
                        preferences = preferences.copy(includeNotes = it)
                    }
                    ExportScopeToggle(stringResource(R.string.transfer_images_attachments), preferences.includeImages, 5, 7) {
                        preferences = preferences.copy(includeImages = it); if (it) encrypt = true
                    }
                    ExportScopeToggle(stringResource(R.string.backup_content_trash), preferences.includeTrash, 6, 7) {
                        preferences = preferences.copy(includeTrash = it, includeTrashAndHistory = it,
                            includeGeneratorHistory = false, includeTimeline = false)
                    }
                }
                ExportScopeToggle(stringResource(R.string.backup_content_webdav_config), preferences.includeWebDavConfig, 0, 1) {
                    preferences = preferences.copy(includeWebDavConfig = it)
                    if (it) encrypt = true
                }
                Text(stringResource(R.string.zip_config_backup_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                ExportScopeToggle(stringResource(R.string.transfer_encrypt_backup), encrypt, 0, 1,
                    enabled = !preferences.includePasskeys && !preferences.includeImages && !preferences.includeWebDavConfig) { encrypt = it }
                Text(stringResource(R.string.transfer_encryption_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (!exporting && option == ExportOption.STEAM_MAFILE) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.padding(16.dp)) { SteamMaFileExportOptionsContent(steamCandidates, selectedSteamIds, loadingSteam,
                        onToggle = { selectedSteamIds = if (it in selectedSteamIds) selectedSteamIds - it else selectedSteamIds + it },
                        onSelectAll = { selectedSteamIds = steamCandidates.map { it.id }.toSet() },
                        onClear = { selectedSteamIds = emptySet() }, onRefresh = ::loadSteam) }
                }
            }
        }
    }
    if (showFormats) ModalBottomSheet(onDismissRequest = { showFormats = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ExportOption.entries.forEachIndexed { index, value ->
                TransferChoiceRow(stringResource(when(value) {
                    ExportOption.ZIP_BACKUP -> R.string.transfer_zip_title
                    ExportOption.KDBX -> R.string.export_option_kdbx
                    ExportOption.STEAM_MAFILE -> R.string.steam_mafile_export_title
                }), "", Icons.Default.Description, selected = option == value, index = index, count = 3,
                    onClick = { formatKey = value.name; DatabaseExportJobs.dismiss(); showFormats = false })
            }
        }
    }
    if (showPassword) ZipEncryptionPasswordDialog(password, confirmation, passwordVisible, passwordError,
        title = stringResource(if (option == ExportOption.KDBX) R.string.export_option_kdbx else R.string.zip_backup_password_title),
        description = stringResource(if (option == ExportOption.KDBX) R.string.transfer_kdbx_password_description else R.string.zip_backup_password_desc),
        onPasswordChange = { password = it; passwordError = null }, onConfirmationChange = { confirmation = it; passwordError = null },
        onPasswordVisibleChange = { passwordVisible = it },
        onDismiss = { showPassword = false; password = ""; confirmation = "" },
        onConfirm = {
            passwordError = when {
                password.isEmpty() -> context.getString(R.string.import_data_password_cannot_be_empty)
                password != confirmation -> context.getString(R.string.transfer_password_mismatch)
                else -> null
            }
            if (passwordError == null) { showPassword = false; chooseLocation(password) }
        })
    if (showSteamRisk) AlertDialog(onDismissRequest = { showSteamRisk = false },
        title = { Text(stringResource(R.string.steam_mafile_export_confirm_title)) },
        text = { Text(stringResource(R.string.steam_mafile_export_confirm_message, selectedSteamIds.size)) },
        confirmButton = { TextButton(onClick = { showSteamRisk = false; showSteamIdentity = true }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { showSteamRisk = false }) { Text(stringResource(R.string.cancel)) } })
    if (showSteamIdentity) M3IdentityVerifyDialog(
        title = stringResource(R.string.verify_identity), message = stringResource(R.string.steam_mafile_export_verify_message),
        passwordValue = identityPassword, onPasswordChange = { identityPassword = it; identityError = false },
        onDismiss = { showSteamIdentity = false; identityPassword = "" },
        onConfirm = {
            if (!security.isMasterPasswordSet() || security.verifyMasterPassword(identityPassword)) {
                showSteamIdentity = false; identityPassword = ""; chooseLocation()
            } else identityError = true
        }, confirmText = stringResource(R.string.start_export),
        confirmEnabled = !security.isMasterPasswordSet() || identityPassword.isNotBlank(), destructiveConfirm = false,
        isPasswordError = identityError, passwordErrorText = stringResource(R.string.current_password_incorrect),
        onBiometricClick = if (context is FragmentActivity && biometricEnabled && biometric.isBiometricAvailable()) ({
            biometric.authenticate(context, context.getString(R.string.verify_identity), context.getString(R.string.steam_mafile_export_verify_subtitle),
                onSuccess = { showSteamIdentity = false; identityPassword = ""; chooseLocation() },
                onError = { scope.launch { snackbar.showSnackbar(it) } }, onFailed = {})
        }) else null,
    )
}

@Composable
private fun ExportScopeToggle(title: String, checked: Boolean, index: Int, count: Int,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, enabled = enabled,
        shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp,
            bottomStart = if (index == count - 1) 24.dp else 4.dp, bottomEnd = if (index == count - 1) 24.dp else 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun ZipEncryptionPasswordDialog(
    password: String,
    confirmation: String,
    passwordVisible: Boolean,
    errorMessage: String?,
    hintMessage: String? = null,
    title: String = stringResource(R.string.zip_backup_password_title),
    description: String = stringResource(R.string.zip_backup_password_desc),
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onPasswordVisibleChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hintMessage != null) {
                    Text(
                        text = hintMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.zip_backup_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.hide_password else R.string.show_password
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null && password.isEmpty(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    label = { Text(stringResource(R.string.zip_backup_password_confirm_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { message ->
                        { Text(message) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.zip_backup_password_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SteamMaFileExportOptionsContent(
    candidates: List<SteamMaFileExportCandidate>,
    selectedIds: Set<Long>,
    isLoading: Boolean,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.steam_mafile_export_selected_count, selectedIds.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll, enabled = candidates.isNotEmpty()) {
                Text(stringResource(R.string.steam_mafile_export_select_all))
            }
            TextButton(onClick = onClear, enabled = selectedIds.isNotEmpty()) {
                Text(stringResource(R.string.steam_mafile_export_clear))
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.steam_mafile_export_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.steam_mafile_export_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        } else {
            candidates.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = account.id in selectedIds,
                        onCheckedChange = { onToggle(account.id) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = account.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

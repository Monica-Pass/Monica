package takagi.ru.monica.ui.screens

import takagi.ru.monica.utils.AppLocaleStringResolver

import takagi.ru.monica.credentialexchange.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import kotlinx.coroutines.CancellationException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.transfer.*
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.util.DataExportImportManager
import takagi.ru.monica.util.FileOperationHelper
import takagi.ru.monica.utils.KeePassOperationException
import takagi.ru.monica.viewmodel.DataExportImportViewModel
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * Resolve the user-facing name returned by Android's document picker.
 * `lastPathSegment` is often an opaque `document:<id>` for content URIs.
 */
internal fun resolveImportFileDisplayName(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    val providerName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    if (providerName != null) return providerName

    return fallbackImportFileDisplayName(
        uriPath = uri.path?.let { runCatching { Uri.decode(it) }.getOrDefault(it) },
        lastPathSegment = uri.lastPathSegment?.let { runCatching { Uri.decode(it) }.getOrDefault(it) },
        fallback = fallback
    )
}

internal fun fallbackImportFileDisplayName(
    uriPath: String?,
    lastPathSegment: String?,
    fallback: String
): String {
    return sequenceOf(
        uriPath?.substringAfterLast('/'),
        lastPathSegment?.substringAfterLast('/')
    ).mapNotNull { candidate ->
        candidate?.trim()?.takeIf {
            it.isNotEmpty() &&
                !it.contains(':') &&
                it != "." &&
                it != ".."
        }
    }.firstOrNull() ?: fallback
}

/**
 * 数据导入界面 - M3 Expressive 设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDataScreen(
    destination: ImportDestination = ImportDestination.Local,
    onDestinationChange: (ImportDestination) -> Unit = {},
    importSummary: ImportResultSummary? = null,
    onResetSummary: () -> Unit = {},
    onImportExchange: suspend (CxfCredentialCodec.Decoded) -> Result<Int> = { Result.failure(IllegalStateException()) },
    onNavigateBack: () -> Unit,
    onImport: suspend (Uri) -> Result<Int>,  // 普通数据导入
    onImportAegis: suspend (Uri) -> Result<Int>,  // Aegis JSON导入
    onImportEncryptedAegis: suspend (Uri, String) -> Result<Int>,  // 加密的Aegis JSON导入
    onImportStratum: suspend (Uri, String?) -> Result<Int> = { _, _ -> Result.failure(Exception("Not implemented")) }, // Stratum 导入
    onImportSteamMaFile: suspend (Uri) -> Result<Int>,  // Steam maFile导入
    onBeginSteamLoginImport: suspend (String, String, String?) -> DataExportImportViewModel.SteamLoginImportState = { _, _, _ ->
        DataExportImportViewModel.SteamLoginImportState.Failure("Not implemented")
    }, // Steam 登录导入（开始）
    onSubmitSteamLoginImportCode: suspend (String, String, Int, String?) -> DataExportImportViewModel.SteamLoginImportState = { _, _, _, _ ->
        DataExportImportViewModel.SteamLoginImportState.Failure("Not implemented")
    }, // Steam 登录导入（提交验证码）
    onClearSteamLoginImportSession: (String) -> Unit = {}, // 清理 Steam 登录会话
    importProgress: takagi.ru.monica.transfer.TransferProgress? = null,
    onImportZip: suspend (Uri, String?, Boolean) -> Result<Int>,  // Monica ZIP导入
    onImportKdbx: suspend (Uri, String, Uri?) -> Result<Int> = { _, _, _ -> Result.failure(Exception("Not implemented")) },  // KDBX导入
    onImportKeePassCsv: suspend (Uri) -> Result<Int> = onImport,  // KeePass CSV导入
    onImportBitwardenCsv: suspend (Uri) -> Result<Int> = onImport,  // Bitwarden CSV导入
    onImportBitwardenAuthenticatorCsv: suspend (Uri) -> Result<Int> = {
        Result.failure(IllegalStateException("Bitwarden Authenticator import is unavailable"))
    },
    onImportProtonPassCsv: suspend (Uri) -> Result<Int> = onImport,  // Proton Pass CSV导入
    onImportChromeCsv: suspend (Uri) -> Result<Int> = onImport,  // Chrome CSV导入
    onImportPasswordKeyboardCsv: suspend (
        Uri,
        DataExportImportManager.PasswordKeyboardTagHandling
    ) -> Result<Int> = { _, _ -> Result.failure(Exception("Not implemented")) }, // 密码键盘软件 CSV导入
    initialApplicationSource: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context.findImportActivity()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val dataJob by DatabaseExportJobs.state.collectAsState()
    val activeImport = dataJob?.takeIf { it.kind == DataTaskKind.IMPORT }
    val backgroundImporter = remember(context, destination.key) { BackgroundImportExecutor(context, destination.key) }
    LaunchedEffect(Unit) { DatabaseExportJobs.recoverInterrupted(context.applicationContext) }
    LaunchedEffect(activeImport?.id) { activeImport?.let { onDestinationChange(ImportDestination.fromKey(it.sourceKey)) } }
    var localImporting by remember { mutableStateOf(false) }
    val isImporting = localImporting || dataJob?.status == ExportJobStatus.RUNNING
    LaunchedEffect(isImporting) { if (isImporting) scrollState.animateScrollTo(0) }
    var applicationSource by rememberSaveable(initialApplicationSource) { mutableStateOf(initialApplicationSource) }
    var showFormatChooser by remember { mutableStateOf(false) }
    var choosingCsvFormat by remember { mutableStateOf(false) }
    // Private credentials live only in memory; never save them into a Bundle or log them.
    var received by remember { mutableStateOf<CxfCredentialCodec.Decoded?>(null) }
    var sourceApp by remember { mutableStateOf("") }
    // Retry registration when this page is reopened after a temporary service failure.
    // Receiving credentials does not depend on this independent export registration succeeding.
    LaunchedEffect(context) { CredentialExchangeRegistrar.register(context.applicationContext) }
    val receiveCredentials: () -> Unit = receive@{
        if (isImporting) return@receive
        localImporting = true
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            received = null
            onResetSummary()
            var errorMessage: Int? = null
            try {
                val response = ProviderEventsManager.create(context).importCredentials(
                    activity ?: context,
                    ImportCredentialsRequest(CredentialExchangeRegistrar.supportedTypes, emptySet()),
                )
                val label = TransferCallingAppVerifier.verifiedLabel(context, response.callingAppInfo)
                    ?: throw SecurityException()
                val decoded = withContext(Dispatchers.IO) { CxfCredentialCodec.decode(response.response.responseJson) }
                sourceApp = label
                received = decoded
                CredentialExchangeErrors.record(context, CredentialExchangeErrors.Operation.IMPORT, decoded = decoded)
            } catch (_: ImportCredentialsCancellationException) {
                // Cancelling the system picker never writes any data.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = CredentialExchangeErrors.message(error)
                CredentialExchangeErrors.record(context, CredentialExchangeErrors.Operation.IMPORT, error)
            } finally { localImporting = false }
            // A Snackbar suspends until dismissed; release the busy state before waiting for it.
            errorMessage?.let { snackbarHostState.showSnackbar(context.getString(it)) }
        }
    }
    var importType by remember { mutableStateOf("monica_zip") } // 默认选择 ZIP 备份
    var csvImportType by remember { mutableStateOf("normal") } // CSV子类型
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showZipRestoreConfirmDialog by remember { mutableStateOf(false) }
    var restoreZipConfig by remember(selectedFileUri) { mutableStateOf(false) }
    var showPasswordKeyboardTagDialog by remember { mutableStateOf(false) }
    var aegisPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    
    // KDBX 导入密码
    var showKdbxPasswordDialog by remember { mutableStateOf(false) }
    var kdbxPassword by remember { mutableStateOf("") }
    var kdbxPasswordVisible by remember { mutableStateOf(false) }
    var kdbxKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var kdbxKeyFileName by remember { mutableStateOf("") }
    var keepassImportError by remember { mutableStateOf<KeePassOperationException?>(null) }
    var showKeepassImportErrorDialog by remember { mutableStateOf(false) }
    val kdbxKeyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri: Uri? ->
        selectedUri?.let {
            kdbxKeyFileUri = it
            kdbxKeyFileName = resolveImportFileDisplayName(context, it, "keyfile")
        }
    }

    var steamImportMode by remember { mutableStateOf("mafile") } // mafile / login
    var steamDeviceIdInput by remember { mutableStateOf("") }
    var steamGuardJsonInput by remember { mutableStateOf("") }
    var steamCustomNameInput by remember { mutableStateOf("") }
    var steamLoginUserNameInput by remember { mutableStateOf("") }
    var steamLoginPasswordInput by remember { mutableStateOf("") }
    var steamLoginPasswordVisible by remember { mutableStateOf(false) }
    var steamLoginChallengeCodeInput by remember { mutableStateOf("") }
    var steamLoginPendingSessionId by remember { mutableStateOf<String?>(null) }
    var steamLoginChallengeType by remember { mutableStateOf(0) }
    var steamLoginChallengeHint by remember { mutableStateOf("") }
    var showSteamPasswordPicker by remember { mutableStateOf(false) }

    val pickerSecurityManager = remember { takagi.ru.monica.security.SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val passwordEntriesForPicker by remember(passwordDatabase) { passwordDatabase.passwordEntryDao()
        .getAllPasswordEntries() }
        .collectAsState(initial = emptyList())
    
    val importTypes = importTypeOptions()
    val csvImportTypes = csvImportTypeOptions()

    val effectiveImportType = if (importType == "csv_group") csvImportType else importType
    val isSteamLoginMode = effectiveImportType == "steam" && steamImportMode == "login"
    
    val currentTypeInfo = if (importType == "csv_group") {
        csvImportTypes.find { it.key == csvImportType } ?: csvImportTypes[0]
    } else {
        importTypes.find { it.key == importType } ?: importTypes[0]
    }
    
    // 设置文件操作回调
    DisposableEffect(context) {
        val fileCallback = object : FileOperationHelper.FileOperationCallback {
            override fun onExportFileSelected(uri: Uri?) {
                // 导入界面不需要处理导出文件选择
            }
            
            override fun onImportFileSelected(uri: Uri?) {
                uri?.let { safeUri ->
                    scope.launch {
                        try {
                            // 获取持久化权限
                            try {
                                context.contentResolver.takePersistableUriPermission(
                                    safeUri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: SecurityException) {
                                // 某些URI可能不支持持久化权限,忽略这个错误
                                android.util.Log.w("ImportDataScreen", "无法获取持久化权限", e)
                            }
                            
                            selectedFileUri = safeUri
                            // DocumentsProvider 的 lastPathSegment 经常是 document:<id>，不能作为文件名。
                            selectedFileName = withContext(Dispatchers.IO) {
                                resolveImportFileDisplayName(
                                    context = context,
                                    uri = safeUri,
                                    fallback = context.getString(R.string.import_data_file_selected)
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            android.util.Log.e("ImportDataScreen", "文件选择异常", e)
                            localImporting = false
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_file_select_failed,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        }
                    }
                }
            }
        }
        FileOperationHelper.setCallback(fileCallback)
        onDispose { FileOperationHelper.clearCallback(fileCallback) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_data_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (applicationSource) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = {
                            val draft = received
                            if (draft == null) receiveCredentials() else scope.launch {
                                localImporting = true
                                try {
                                    backgroundImporter.run(onImportExchange, draft).onSuccess { received = null }.onFailure {
                                        localImporting = false
                                        snackbarHostState.showSnackbar(context.getString(R.string.exchange_failed))
                                    }
                                } finally { localImporting = false }
                            }
                        },
                        enabled = !isImporting && (received == null || received!!.passwordCount + received!!.passkeyCount > 0),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        if (isImporting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(if (received == null) Icons.Default.SwapHoriz else Icons.Default.Download, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (isImporting) R.string.importing else if (received == null)
                            R.string.exchange_choose_app else R.string.start_import))
                    }
                }
            } else Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 导入按钮
                    Button(
                        onClick = {
                            if (isSteamLoginMode) {
                                scope.launch {
                                    localImporting = true
                                    try {
                                        val customName = steamCustomNameInput.trim().takeIf { it.isNotBlank() }
                                        val loginState = if (steamLoginPendingSessionId.isNullOrBlank()) {
                                            onBeginSteamLoginImport(
                                                steamLoginUserNameInput.trim(),
                                                steamLoginPasswordInput,
                                                customName
                                            )
                                        } else {
                                            onSubmitSteamLoginImportCode(
                                                steamLoginPendingSessionId.orEmpty(),
                                                steamLoginChallengeCodeInput.trim(),
                                                steamLoginChallengeType,
                                                customName
                                            )
                                        }

                                        when (loginState) {
                                            is DataExportImportViewModel.SteamLoginImportState.ChallengeRequired -> {
                                                steamLoginPendingSessionId = loginState.pendingSessionId
                                                steamLoginChallengeType = loginState.challenges.firstOrNull()?.confirmationType ?: 0
                                                steamLoginChallengeHint = loginState.challenges.firstOrNull()?.associatedMessage.orEmpty()
                                                // 每次进入挑战阶段都清空输入框，避免二次提交用到旧验证码
                                                steamLoginChallengeCodeInput = ""
                                                localImporting = false
                                                snackbarHostState.showSnackbar(
                                                    loginState.message
                                                        ?: context.getString(R.string.import_type_steam_login_challenge_required)
                                                )
                                            }

                                            is DataExportImportViewModel.SteamLoginImportState.Imported -> {
                                                steamLoginPendingSessionId = null
                                                steamLoginChallengeType = 0
                                                steamLoginChallengeCodeInput = ""
                                                steamLoginChallengeHint = ""
                                                localImporting = false
                                                handleImportResult(
                                                    Result.success(loginState.count),
                                                    context,
                                                    snackbarHostState,
                                                    effectiveImportType,
                                                    onNavigateBack
                                                )
                                            }

                                            is DataExportImportViewModel.SteamLoginImportState.Failure -> {
                                                localImporting = false
                                                snackbarHostState.showSnackbar(loginState.message)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (e is CancellationException) {
                                            throw e
                                        }
                                        android.util.Log.e("ImportDataScreen", "导入异常", e)
                                        localImporting = false
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                R.string.import_data_error_exception,
                                                e.message ?: context.getString(R.string.import_data_unknown_error)
                                            )
                                        )
                                    } finally {
                                        localImporting = false
                                    }
                                }
                            } else {
                                selectedFileUri?.let { uri ->
                                    scope.launch {
                                        localImporting = true
                                        try {
                                            when (effectiveImportType) {
                                                "monica_zip" -> {
                                                    localImporting = false
                                                    showZipRestoreConfirmDialog = true
                                                    return@launch
                                                }
                                                "aegis" -> {
                                                    // Aegis导入类型，先检查是否为加密文件
                                                    val isEncryptedResult = DataExportImportManager(context).isEncryptedAegisFile(uri)
                                                    val isEncrypted = isEncryptedResult.getOrDefault(false)
                                                    if (isEncrypted) {
                                                        // 是加密文件，显示密码输入对话框
                                                        localImporting = false
                                                        showPasswordDialog = true
                                                        passwordError = null
                                                        aegisPassword = ""
                                                        return@launch
                                                    } else {
                                                        // 不是加密文件，直接导入
                                                        val result = backgroundImporter.run(onImportAegis, uri)
                                                        localImporting = false
                                                        handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                    }
                                                }
                                                "stratum" -> {
                                                    val result = backgroundImporter.run(onImportStratum, uri, null)
                                                    localImporting = false
                                result.onSuccess { count ->
                                                        localImporting = false
                                                        handleImportResult(Result.success(count), context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                    }.onFailure { error ->
                                                        val errorMsg = error.message ?: ""
                                                        if (isPasswordRequiredError(errorMsg, AppLocaleStringResolver(context))) {
                                                            localImporting = false
                                                            showPasswordDialog = true
                                                            passwordError = null
                                                            aegisPassword = ""
                                                        } else {
                                                            localImporting = false
                                                            handleImportResult(Result.failure(error), context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                        }
                                                    }
                                                }
                                                "steam" -> {
                                                    // Steam maFile导入
                                                    val result = backgroundImporter.run(onImportSteamMaFile, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "kdbx" -> {
                                                    // KDBX 导入需要密码
                                                    if (isLikelyLegacyKdbFile(selectedFileName, uri)) {
                                                        localImporting = false
                                                        snackbarHostState.showSnackbar(
                                                            context.getString(R.string.import_data_keepass_legacy_kdb_unsupported)
                                                        )
                                                        return@launch
                                                    }
                                                    localImporting = false
                                                    showKdbxPasswordDialog = true
                                                    kdbxPassword = ""
                                                    kdbxKeyFileUri = null
                                                    kdbxKeyFileName = ""
                                                }
                                                "keepass_csv" -> {
                                                    val result = backgroundImporter.run(onImportKeePassCsv, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "bitwarden_csv" -> {
                                                    val result = backgroundImporter.run(onImportBitwardenCsv, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "bitwarden_authenticator_csv" -> {
                                                    val result = backgroundImporter.run(onImportBitwardenAuthenticatorCsv, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "proton_pass_csv" -> {
                                                    val result = backgroundImporter.run(onImportProtonPassCsv, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "chrome_csv" -> {
                                                    val result = backgroundImporter.run(onImportChromeCsv, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "password_keyboard_csv" -> {
                                                    val shouldPrompt = shouldPromptPasswordKeyboardTagDialog(context, uri)
                                                    if (shouldPrompt) {
                                                        localImporting = false
                                                        showPasswordKeyboardTagDialog = true
                                                        return@launch
                                                    }

                                                    val result = backgroundImporter.run(onImportPasswordKeyboardCsv,
                                                        uri,
                                                        DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
                                                    )
                                                    localImporting = false
                                                    handleImportResult(
                                                        result,
                                                        context,
                                                        snackbarHostState,
                                                        effectiveImportType,
                                                        onNavigateBack
                                                    )
                                                }
                                                else -> {
                                                    // 普通CSV导入
                                                    val result = backgroundImporter.run(onImport, uri)
                                                    localImporting = false
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) {
                                                throw e
                                            }
                                            android.util.Log.e("ImportDataScreen", "导入异常", e)
                                            localImporting = false
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.import_data_error_exception,
                                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                                )
                                            )
                                        } finally {
                                            localImporting = false
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        enabled = if (isSteamLoginMode) {
                            if (steamLoginPendingSessionId.isNullOrBlank()) {
                                steamLoginUserNameInput.isNotBlank() &&
                                    steamLoginPasswordInput.isNotBlank() &&
                                    !isImporting
                            } else {
                                steamLoginChallengeCodeInput.isNotBlank() && !isImporting
                            }
                        } else {
                            selectedFileUri != null && !isImporting
                        },
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.importing), style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isSteamLoginMode && !steamLoginPendingSessionId.isNullOrBlank())
                                    stringResource(R.string.import_type_steam_login_submit_code)
                                else
                                    stringResource(R.string.start_import),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    

                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (applicationSource || effectiveImportType !in listOf("kdbx", "steam")) {
                TransferDestinationField(destination, onDestinationChange, enabled = !isImporting)
                activeImport?.takeIf { it.status != ExportJobStatus.RUNNING }?.let { BackgroundTaskResultCard(it) }

                if (isImporting) {
                    takagi.ru.monica.transfer.TransferProgressCard(
                        dataJob?.progress ?: importProgress ?: takagi.ru.monica.transfer.TransferProgress(),
                        background = dataJob?.status == ExportJobStatus.RUNNING)
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.exchange_apps, R.string.exchange_files).forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = applicationSource == (index == 0),
                        onClick = { applicationSource = index == 0; received = null; onResetSummary() },
                        enabled = !isImporting, shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
            }
            (activeImport?.importSummary ?: importSummary)?.let { TransferSummary(it) }
            if (applicationSource) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.SwapHoriz, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (received == null) R.string.exchange_import_heading else R.string.exchange_received),
                            style = MaterialTheme.typography.headlineSmall)
                        if (received == null) Text(stringResource(R.string.exchange_import_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            Text(sourceApp, style = MaterialTheme.typography.titleMedium)
                            TransferCredentialCounts(received!!.passwordCount, received!!.passkeyCount, received!!.skippedCount,
                                skippedReasons = received!!.skipped)
                            Text(stringResource(R.string.exchange_skip_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = receiveCredentials, enabled = !isImporting) {
                                Text(stringResource(R.string.exchange_choose_app))
                            }
                        }
                    }
                }
            } else {
            TransferChoiceRow(
                title = currentTypeInfo.title, subtitle = currentTypeInfo.description, icon = currentTypeInfo.icon,
                enabled = !isImporting,
                onClick = { choosingCsvFormat = false; showFormatChooser = true },
            )
            if (effectiveImportType == "monica_zip" || effectiveImportType == "kdbx") {
                Text(stringResource(if (effectiveImportType == "kdbx") R.string.exchange_native_kdbx_notice
                    else R.string.exchange_zip_target_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (effectiveImportType == "steam") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.import_type_steam_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = steamImportMode == "mafile",
                                onClick = {
                                    if (steamLoginPendingSessionId != null) {
                                        onClearSteamLoginImportSession(steamLoginPendingSessionId.orEmpty())
                                    }
                                    steamImportMode = "mafile"
                                    steamDeviceIdInput = ""
                                    steamGuardJsonInput = ""
                                    steamCustomNameInput = ""
                                    steamLoginPendingSessionId = null
                                    steamLoginChallengeType = 0
                                    steamLoginChallengeCodeInput = ""
                                    steamLoginChallengeHint = ""
                                },
                                label = { Text(stringResource(R.string.import_type_steam_mode_mafile)) }
                            )
                            FilterChip(
                                selected = steamImportMode == "login",
                                onClick = {
                                    steamImportMode = "login"
                                    selectedFileUri = null
                                    selectedFileName = null
                                    steamDeviceIdInput = ""
                                    steamGuardJsonInput = ""
                                    steamLoginChallengeCodeInput = ""
                                    steamLoginChallengeHint = ""
                                },
                                label = { Text(stringResource(R.string.import_type_steam_mode_login)) }
                            )
                        }

                        if (steamImportMode == "login") {
                            Text(
                                stringResource(R.string.import_type_steam_login_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { showSteamPasswordPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = steamLoginPendingSessionId.isNullOrBlank()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.autofill_select_password))
                            }
                            OutlinedTextField(
                                value = steamLoginUserNameInput,
                                onValueChange = { steamLoginUserNameInput = it },
                                label = { Text(stringResource(R.string.import_type_steam_login_username_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = steamLoginPendingSessionId.isNullOrBlank()
                            )
                            OutlinedTextField(
                                value = steamLoginPasswordInput,
                                onValueChange = { steamLoginPasswordInput = it },
                                label = { Text(stringResource(R.string.import_type_steam_login_password_label)) },
                                visualTransformation = if (steamLoginPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { steamLoginPasswordVisible = !steamLoginPasswordVisible }) {
                                        Icon(
                                            imageVector = if (steamLoginPasswordVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (steamLoginPasswordVisible) {
                                                stringResource(R.string.hide_password)
                                            } else {
                                                stringResource(R.string.show_password)
                                            }
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = steamLoginPendingSessionId.isNullOrBlank()
                            )
                            if (!steamLoginPendingSessionId.isNullOrBlank()) {
                                if (steamLoginChallengeHint.isNotBlank()) {
                                    Text(
                                        steamLoginChallengeHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedTextField(
                                    value = steamLoginChallengeCodeInput,
                                    onValueChange = { steamLoginChallengeCodeInput = it },
                                    label = { Text(stringResource(R.string.import_type_steam_login_code_label)) },
                                    placeholder = { Text(stringResource(R.string.import_type_steam_login_code_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = steamCustomNameInput,
                                onValueChange = { steamCustomNameInput = it },
                                label = { Text(stringResource(R.string.import_type_steam_custom_name_label)) },
                                placeholder = { Text(stringResource(R.string.import_type_steam_custom_name_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (!isSteamLoginMode) {
                // 文件选择区域
                Text(
                    stringResource(R.string.import_data_select_file),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                TransferChoiceRow(
                    title = stringResource(if (selectedFileUri != null) R.string.import_data_file_selected
                        else R.string.import_data_tap_to_select_file),
                    subtitle = selectedFileName ?: currentTypeInfo.fileHint,
                    icon = if (selectedFileUri != null) Icons.Default.InsertDriveFile else Icons.Default.FileOpen,
                    selected = selectedFileUri != null,
                    enabled = !isImporting,
                    onClick = {
                        activity?.let { act ->
                            // 根据导入类型选择不同的文件过滤器
                            when (effectiveImportType) {
                                "monica_zip" -> FileOperationHelper.importFromZip(act)
                                "kdbx" -> FileOperationHelper.importFromKdbx(act)
                                "keepass_csv" -> FileOperationHelper.importFromCsv(act)
                                "bitwarden_csv" -> FileOperationHelper.importFromCsv(act)
                                "proton_pass_csv" -> FileOperationHelper.importFromCsv(act)
                                "chrome_csv" -> FileOperationHelper.importFromCsv(act)
                                "password_keyboard_csv" -> FileOperationHelper.importFromCsv(act)
                                "aegis" -> FileOperationHelper.importFromJson(act)
                                "stratum" -> FileOperationHelper.importFromStratum(act)
                                "steam" -> FileOperationHelper.importFromMaFile(act)
                                else -> FileOperationHelper.importFromCsv(act)
                            }
                        } ?: run {
                            scope.launch {
                                localImporting = false
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.error_launch_export,
                                        context.getString(R.string.import_data_operation_unavailable)
                                    )
                                )
                            }
                        }
                    },
                )
                Text(
                    stringResource(R.string.import_data_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            
            } // Files
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showFormatChooser) {
        ModalBottomSheet(onDismissRequest = { showFormatChooser = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (choosingCsvFormat) TextButton(onClick = { choosingCsvFormat = false }) {
                    Icon(Icons.Default.ArrowBack, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_data_select_type))
                }
                Text(stringResource(if (choosingCsvFormat) R.string.import_type_csv_source_title else R.string.import_data_select_type),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp, 16.dp))
                val formats = if (choosingCsvFormat) csvImportTypes else importTypes
                formats.forEachIndexed { index, format ->
                    TransferChoiceRow(format.title, format.fileHint, format.icon, index = index, count = formats.size,
                        selected = if (choosingCsvFormat) importType == "csv_group" && csvImportType == format.key else importType == format.key,
                        onClick = {
                            if (format.key == "csv_group") choosingCsvFormat = true else {
                                steamLoginPendingSessionId?.let(onClearSteamLoginImportSession)
                                steamLoginPendingSessionId = null
                                if (choosingCsvFormat) { importType = "csv_group"; csvImportType = format.key }
                                else importType = format.key
                                selectedFileUri = null
                                selectedFileName = null
                                onResetSummary()
                                showFormatChooser = false
                            }
                        })
                }
            }
        }
    }
    if (showSteamPasswordPicker) {
        PasswordEntryPickerBottomSheet(
            visible = true,
            title = stringResource(R.string.select_password_to_bind),
            passwords = passwordEntriesForPicker.filter { !it.isDeleted && !it.isArchived },
            onDismiss = { showSteamPasswordPicker = false },
            onSelect = { entry ->
                val resolvedUsername = runCatching { pickerSecurityManager.decryptData(entry.username) }
                    .getOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: entry.username.trim()
                val resolvedPassword = runCatching { pickerSecurityManager.decryptData(entry.password) }
                    .getOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: entry.password.trim()

                steamLoginUserNameInput = resolvedUsername
                steamLoginPasswordInput = resolvedPassword
                showSteamPasswordPicker = false
                Toast.makeText(
                    context,
                    context.getString(R.string.steam_login_fill_from_password_applied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    if (showPasswordKeyboardTagDialog) {
        PasswordKeyboardTagDialog(
            onDismiss = { showPasswordKeyboardTagDialog = false },
            onConvert = {
                val uri = selectedFileUri
                if (uri == null) {
                    showPasswordKeyboardTagDialog = false
                } else {
                    showPasswordKeyboardTagDialog = false
                    scope.launch {
                        localImporting = true
                        try {
                            val result = backgroundImporter.run(onImportPasswordKeyboardCsv,
                                uri,
                                DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
                            )
                            localImporting = false
                            handleImportResult(
                                result,
                                context,
                                snackbarHostState,
                                effectiveImportType,
                                onNavigateBack
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            android.util.Log.e("ImportDataScreen", "密码键盘 CSV 导入异常", e)
                            localImporting = false
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            localImporting = false
                        }
                    }
                }
            },
            onDrop = {
                val uri = selectedFileUri
                if (uri == null) {
                    showPasswordKeyboardTagDialog = false
                } else {
                    showPasswordKeyboardTagDialog = false
                    scope.launch {
                        localImporting = true
                        try {
                            val result = backgroundImporter.run(onImportPasswordKeyboardCsv,
                                uri,
                                DataExportImportManager.PasswordKeyboardTagHandling.DROP
                            )
                            localImporting = false
                            handleImportResult(
                                result,
                                context,
                                snackbarHostState,
                                effectiveImportType,
                                onNavigateBack
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            android.util.Log.e("ImportDataScreen", "密码键盘 CSV 导入异常", e)
                            localImporting = false
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            localImporting = false
                        }
                    }
                }
            }
        )
    }

    if (showZipRestoreConfirmDialog) {
        ZipRestoreConfirmDialog(
            selectedFileName = selectedFileName,
            isImporting = isImporting,
            restoreConfig = restoreZipConfig,
            onRestoreConfigChange = { restoreZipConfig = it },
            onDismiss = { showZipRestoreConfirmDialog = false },
            onConfirm = {
                val uri = selectedFileUri
                if (uri != null) {
                    showZipRestoreConfirmDialog = false
                    scope.launch {
                        localImporting = true
                        try {
                            val result = backgroundImporter.run(onImportZip, uri, null, restoreZipConfig)
                            localImporting = false
                            result.onSuccess { count ->
                                localImporting = false
                                handleImportResult(
                                    Result.success(count),
                                    context,
                                    snackbarHostState,
                                    effectiveImportType,
                                    onNavigateBack
                                )
                            }.onFailure { error ->
                                if (error is takagi.ru.monica.utils.WebDavHelper.PasswordRequiredException) {
                                    localImporting = false
                                    showPasswordDialog = true
                                    passwordError = null
                                    aegisPassword = ""
                                } else {
                                    localImporting = false
                                    handleImportResult(
                                        Result.failure(error),
                                        context,
                                        snackbarHostState,
                                        effectiveImportType,
                                        onNavigateBack
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            android.util.Log.e("ImportDataScreen", "ZIP导入异常", e)
                            localImporting = false
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            localImporting = false
                        }
                    }
                }
            }
        )
    }

    // 密码输入对话框
    if (showPasswordDialog) {
        EncryptedImportPasswordDialog(
            importType = importType,
            password = aegisPassword,
            passwordError = passwordError,
            isImporting = isImporting,
            onPasswordChange = {
                aegisPassword = it
                passwordError = null
            },
            onDismiss = {
                showPasswordDialog = false
                aegisPassword = ""
                passwordError = null
            },
            onConfirm = {
                if (aegisPassword.isEmpty()) {
                    passwordError = context.getString(R.string.import_data_password_cannot_be_empty)
                } else {
                    scope.launch {
                        localImporting = true
                        showPasswordDialog = false
                        try {
                            selectedFileUri?.let { uri ->
                                // 使用加密导入回调
                                val result = when (importType) {
                                    "monica_zip" -> backgroundImporter.run(onImportZip, uri, aegisPassword, restoreZipConfig)
                                    "stratum" -> backgroundImporter.run(onImportStratum, uri, aegisPassword)
                                    else -> backgroundImporter.run(onImportEncryptedAegis, uri, aegisPassword)
                                }

                                localImporting = false
                                result.onSuccess { count ->
                                    val message = if (importType == "monica_zip") {
                                        context.getString(R.string.import_data_zip_restore_success_count, count)
                                    } else if (importType == "stratum") {
                                        context.getString(R.string.import_data_stratum_import_success_count, count)
                                    } else {
                                        context.getString(R.string.import_data_aegis_import_success_count, count)
                                    }
                                    localImporting = false
                                    snackbarHostState.showSnackbar(message)
                                }.onFailure { error ->
                                    val errorMsg = error.message ?: context.getString(R.string.import_data_unknown_error)
                                    if ((error.cause is javax.crypto.AEADBadTagException || isPasswordDecryptError(errorMsg, AppLocaleStringResolver(context)))) {
                                        passwordError = context.getString(R.string.import_data_password_incorrect_retry)
                                        showPasswordDialog = true
                                    } else {
                                        localImporting = false
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.import_data_failed_with_reason, errorMsg)
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            android.util.Log.e("ImportDataScreen", "加密导入异常", e)
                            localImporting = false
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_failed_with_reason,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            localImporting = false
                        }
                    }
                }
            }
        )
    }
    
    // KDBX 密码输入对话框
    if (showKdbxPasswordDialog) {
        KdbxImportPasswordDialog(
            password = kdbxPassword,
            passwordVisible = kdbxPasswordVisible,
            keyFileName = kdbxKeyFileName,
            hasKeyFile = kdbxKeyFileUri != null,
            isImporting = isImporting,
            onPasswordChange = { kdbxPassword = it },
            onTogglePasswordVisible = { kdbxPasswordVisible = !kdbxPasswordVisible },
            onPickKeyFile = { kdbxKeyFilePickerLauncher.launch(arrayOf("*/*")) },
            onDismiss = {
                showKdbxPasswordDialog = false
                kdbxPassword = ""
                kdbxKeyFileUri = null
                kdbxKeyFileName = ""
            },
            onConfirm = {
                showKdbxPasswordDialog = false
                selectedFileUri?.let { uri ->
                    scope.launch {
                        localImporting = true
                        try {
                            val result = backgroundImporter.run(onImportKdbx, uri, kdbxPassword, kdbxKeyFileUri)
                            result.onSuccess { count ->
                                localImporting = false
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.import_data_kdbx_import_success_count, count)
                                )
                            }.onFailure { error ->
                                if (error is KeePassOperationException) {
                                    keepassImportError = error
                                    showKeepassImportErrorDialog = true
                                } else {
                                    localImporting = false
                                    snackbarHostState.showSnackbar(
                                        formatImportErrorMessage(
                                            error,
                                            context.getString(R.string.import_data_error)
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                throw e
                            }
                            if (e is KeePassOperationException) {
                                keepassImportError = e
                                showKeepassImportErrorDialog = true
                            } else {
                                localImporting = false
                                snackbarHostState.showSnackbar(
                                    formatImportErrorMessage(
                                        e,
                                        context.getString(
                                            R.string.import_data_error_exception,
                                            e.message ?: context.getString(R.string.import_data_unknown_error)
                                        )
                                    )
                                )
                            }
                        } finally {
                            localImporting = false
                            kdbxPassword = ""
                            kdbxKeyFileUri = null
                            kdbxKeyFileName = ""
                        }
                    }
                }
            }
        )
    }

    if (showKeepassImportErrorDialog && keepassImportError != null) {
        val error = keepassImportError!!
        KeepassImportErrorDialog(
            error = error,
            onDismiss = {
                showKeepassImportErrorDialog = false
                keepassImportError = null
            }
        )
    }
}

// 处理导入结果的辅助函数
private suspend fun handleImportResult(
    result: Result<Int>,
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    importType: String,
    onNavigateBack: () -> Unit
) {
    result.onSuccess { count ->
        val message = when (importType) {
            "aegis" -> context.getString(R.string.import_data_aegis_import_success_count, count)
            "stratum" -> context.getString(R.string.import_data_stratum_import_success_count, count)
            "steam" -> context.getString(R.string.import_data_steam_import_success)
            else -> context.getString(R.string.import_data_success_normal, count)
        }
        snackbarHostState.showSnackbar(message)

    }.onFailure { error ->
        snackbarHostState.showSnackbar(
            formatImportErrorMessage(error, context.getString(R.string.import_data_error))
        )
    }
}

private tailrec fun Context.findImportActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findImportActivity()
    else -> null
}

package takagi.ru.monica.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.toCreationOptions
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.KeePassFileNameResolver
import takagi.ru.monica.utils.KeePassUriPermissionState

private class KeePassOpenDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

private const val GOOGLE_DRIVE_ENTRY_ENABLED = false

/**
 * 本地 KeePass 数据库管理页面
 * M3 Expressive Design
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalKeePassScreen(
    viewModel: LocalKeePassViewModel,
    onNavigateBack: () -> Unit,
    onNavigateNativeEntry: (KeePassNativeResolvedRoute) -> Unit
) {
    val context = LocalContext.current
    val allDatabases by viewModel.allDatabases.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val verificationStates by viewModel.verificationStates.collectAsState()
    val uriPermissionStates by viewModel.uriPermissionStates.collectAsState()
    val keyFileAccessStates by viewModel.keyFileAccessStates.collectAsState()
    val activeNativeManagerDatabaseId by viewModel.activeNativeManagerDatabaseId.collectAsState()
    val conflictState by viewModel.conflictResolution.state.collectAsStateWithLifecycle()
    
    var selectedSource by rememberSaveable { mutableStateOf<KeePassManagementSource?>(null) }
    val localGridState = rememberLazyGridState()
    val webDavGridState = rememberLazyGridState()
    val oneDriveGridState = rememberLazyGridState()
    val googleDriveGridState = rememberLazyGridState()
    var createInCloud by remember { mutableStateOf(false) }

    // 对话框状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showWebDavAttachSheet by remember { mutableStateOf(false) }
    var showOneDriveAttachSheet by remember { mutableStateOf(false) }
    var showGoogleDriveAttachSheet by remember { mutableStateOf(false) }
    var selectedDatabase by remember { mutableStateOf<LocalKeePassDatabase?>(null) }
    var showDatabaseDetailSheet by remember { mutableStateOf(false) }
    var databaseToExport by remember { mutableStateOf<LocalKeePassDatabase?>(null) }
    var databaseToTransferExternal by remember { mutableStateOf<LocalKeePassDatabase?>(null) }
    var keyFileCopyDatabaseId by remember { mutableStateOf<Long?>(null) }
    var keyFileExportDatabase by remember { mutableStateOf<LocalKeePassDatabase?>(null) }
    var selectedExternalUri by remember { mutableStateOf<Uri?>(null) }
    var permissionRepairDatabaseId by remember { mutableStateOf<Long?>(null) }

    conflictState?.let { state ->
        KeePassConflictResolutionSheet(
            state = state,
            onDismiss = viewModel.conflictResolution::dismiss,
            onRefresh = viewModel.conflictResolution::refresh,
            onDecision = viewModel.conflictResolution::submit
        )
    }

    val nativeManagerDatabase = activeNativeManagerDatabaseId?.let { databaseId ->
        allDatabases.firstOrNull { database -> database.id == databaseId }
    }
    nativeManagerDatabase?.let { database ->
        KeePassNativeManagerScreen(
            database = database,
            viewModel = viewModel,
            onNavigateBack = { viewModel.closeNativeManager(database.id) },
            onNavigateSpecialized = onNavigateNativeEntry
        )
        return
    }
    
    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = KeePassOpenDocumentContract()
    ) { uri: Uri? ->
        uri?.let {
            selectedExternalUri = it
            showImportDialog = true
        }
    }

    val permissionRepairLauncher = rememberLauncherForActivityResult(
        contract = KeePassOpenDocumentContract()
    ) { uri: Uri? ->
        val databaseId = permissionRepairDatabaseId
        permissionRepairDatabaseId = null
        if (uri != null && databaseId != null) {
            viewModel.reauthorizeExternalDatabase(databaseId, uri)
        }
    }

    val keyFileCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val databaseId = keyFileCopyDatabaseId
        keyFileCopyDatabaseId = null
        if (uri != null && databaseId != null) {
            viewModel.keepKeyFileCopy(databaseId, uri)
        }
    }

    val keyFileExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val database = keyFileExportDatabase
        keyFileExportDatabase = null
        if (uri != null && database != null) {
            viewModel.exportKeyFileCopy(database.id, uri)
        }
    }

    // 内部数据库导出文件创建选择器
    val exportToExternalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-keepass")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            databaseToExport?.let { db ->
                viewModel.exportToExternal(db.id, targetUri)
            }
        }
        databaseToExport = null
    }
    
    // 外部转移文件创建选择器
    val transferToExternalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-keepass")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            databaseToTransferExternal?.let { db ->
                viewModel.transferDatabase(db.id, KeePassStorageLocation.EXTERNAL, targetUri)
            }
        }
        databaseToTransferExternal = null
    }
    
    // 处理操作状态
    LaunchedEffect(operationState) {
        when (operationState) {
            is LocalKeePassViewModel.OperationState.Success -> {
                // 可以显示 snackbar
                kotlinx.coroutines.delay(2000)
                viewModel.clearOperationState()
            }
            is LocalKeePassViewModel.OperationState.Error -> {
                kotlinx.coroutines.delay(3000)
                viewModel.clearOperationState()
            }
            else -> {}
        }
    }

    fun openDatabaseDetail(database: LocalKeePassDatabase) {
        selectedDatabase = database
        showDatabaseDetailSheet = true
        viewModel.verifyDatabaseCredentials(database.id, force = false)
        viewModel.refreshKeyFileAccessState(database.id)
    }

    LaunchedEffect(allDatabases.map { it.id }) {
        viewModel.pruneVerificationStates(allDatabases.map { it.id })
    }
    
    BackHandler(enabled = selectedSource != null) { selectedSource = null }

    fun openSource(source: KeePassManagementSource, create: Boolean) {
        createInCloud = create
        when (source) {
            KeePassManagementSource.LOCAL -> if (create) showCreateDialog = true
                else filePickerLauncher.launch(arrayOf("*/*"))
            KeePassManagementSource.WEBDAV -> showWebDavAttachSheet = true
            KeePassManagementSource.ONEDRIVE -> showOneDriveAttachSheet = true
            KeePassManagementSource.GOOGLE_DRIVE -> if (GOOGLE_DRIVE_ENTRY_ENABLED) showGoogleDriveAttachSheet = true
        }
    }

    Scaffold(
        topBar = {
            DatabaseManagementTopAppBar(
                title = { Text(selectedSource?.title() ?: stringResource(R.string.local_keepass_section_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (selectedSource != null) selectedSource = null else onNavigateBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = operationState != LocalKeePassViewModel.OperationState.Idle) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OperationStatusBar(operationState)
                }
            }
            val source = selectedSource
            if (source == null) {
                KeePassManagementHub(allDatabases, GOOGLE_DRIVE_ENTRY_ENABLED) { selectedSource = it }
            } else {
                KeePassSourceManagementPage(
                    source = source,
                    databases = remember(allDatabases, source) { allDatabases.filter(source::contains) },
                    verificationStates = verificationStates,
                    onCreateClick = { openSource(source, create = true) },
                    onOpenClick = { openSource(source, create = false) },
                    onOpenDatabase = ::openDatabaseDetail,
                    onResolveConflict = { viewModel.conflictResolution.open(it.id, it.name) },
                    gridState = when (source) {
                        KeePassManagementSource.LOCAL -> localGridState
                        KeePassManagementSource.WEBDAV -> webDavGridState
                        KeePassManagementSource.ONEDRIVE -> oneDriveGridState
                        KeePassManagementSource.GOOGLE_DRIVE -> googleDriveGridState
                    },
                    sourceActionsEnabled = source != KeePassManagementSource.GOOGLE_DRIVE || GOOGLE_DRIVE_ENTRY_ENABLED
                )
            }
        }
    }

    // 创建数据库 BottomSheet
    if (showCreateDialog) {
        CreateKeePassDatabaseBottomSheet(
            onDismiss = { showCreateDialog = false },
            onGenerateKeyFile = { uri -> viewModel.generateKeyFile(uri) },
            onCreate = { name, password, location, externalUri, keyFileUri, options, keepKeyFileCopy ->
                viewModel.createDatabase(
                    name,
                    password,
                    location,
                    externalUri,
                    keyFileUri,
                    options,
                    null,
                    keepKeyFileCopy,
                )
                showCreateDialog = false
            }
        )
    }
    
    // 导入数据库对话框
    if (showImportDialog && selectedExternalUri != null) {
        ImportExternalDatabaseDialog(
            uri = selectedExternalUri!!,
            onDismiss = { 
                showImportDialog = false
                selectedExternalUri = null
            },
            onImport = { name, password, keyFileUri, keepKeyFileCopy ->
                viewModel.importExternalDatabase(
                    name,
                    selectedExternalUri!!,
                    password,
                    keyFileUri,
                    null,
                    keepKeyFileCopy,
                )
                showImportDialog = false
                selectedExternalUri = null
            }
        )
    }

    if (showWebDavAttachSheet) {
        AttachWebDavDatabaseBottomSheet(
            viewModel = viewModel,
            startWithCreate = createInCloud,
            onDismiss = { showWebDavAttachSheet = false },
        )
    }

    if (showOneDriveAttachSheet) {
        AttachOneDriveDatabaseBottomSheet(
            viewModel = viewModel,
            startWithCreate = createInCloud,
            onDismiss = { showOneDriveAttachSheet = false },
        )
    }

    if (GOOGLE_DRIVE_ENTRY_ENABLED && showGoogleDriveAttachSheet) {
        AttachGoogleDriveDatabaseBottomSheet(
            viewModel = viewModel,
            startWithCreate = createInCloud,
            onDismiss = { showGoogleDriveAttachSheet = false },
        )
    }
    
    // 数据库详情底部弹窗
    if (showDatabaseDetailSheet && selectedDatabase != null) {
        KeePassDatabaseDetailBottomSheet(
            database = allDatabases.firstOrNull { it.id == selectedDatabase!!.id } ?: selectedDatabase!!,
            verificationState = verificationStates[selectedDatabase!!.id] ?: LocalKeePassViewModel.VerificationState.Unknown,
            permissionState = uriPermissionStates[selectedDatabase!!.id]
                ?: viewModel.uriPermissionState(selectedDatabase!!),
            keyFileAccessState = keyFileAccessStates[selectedDatabase!!.id]
                ?: LocalKeePassViewModel.KeyFileAccessState.CHECKING,
            onDismiss = { 
                showDatabaseDetailSheet = false
                selectedDatabase = null
            },
            onSetDefault = { viewModel.setAsDefault(it.id) },
            onDelete = { viewModel.deleteDatabase(it.id, deleteFile = false) },
            onTransferToInternal = { viewModel.transferDatabase(it.id, KeePassStorageLocation.INTERNAL) },
            onTransferToExternal = { db ->
                // 保存要转移的数据库，关闭弹窗，打开文件选择器
                databaseToTransferExternal = db
                showDatabaseDetailSheet = false
                selectedDatabase = null
                transferToExternalLauncher.launch("${db.name}.kdbx")
            },
            onVerifyPassword = { db, password, keyFileUri ->
                viewModel.reverifyDatabasePassword(db.id, password, keyFileUri)
            },
            onSyncRemote = { db ->
                viewModel.syncRemoteDatabase(db.id)
            },
            onResolveConflict = { db -> viewModel.conflictResolution.open(db.id, db.name) },
            onExport = { db ->
                databaseToExport = db
                showDatabaseDetailSheet = false
                selectedDatabase = null
                val exportFileName = if (db.name.endsWith(".kdbx", ignoreCase = true)) {
                    db.name
                } else {
                    "${db.name}.kdbx"
                }
                exportToExternalLauncher.launch(exportFileName)
            },
            onRepairPermission = { db ->
                permissionRepairDatabaseId = db.id
                permissionRepairLauncher.launch(arrayOf("application/x-keepass", "application/octet-stream", "*/*"))
            },
            onKeepKeyFileCopy = { db ->
                keyFileCopyDatabaseId = db.id
                keyFileCopyLauncher.launch(arrayOf("*/*"))
            },
            onExportKeyFileCopy = { db ->
                keyFileExportDatabase = db
                keyFileExportLauncher.launch(db.keyFileName ?: "${db.name}.key")
            },
            onDeleteKeyFileCopy = { db -> viewModel.deleteKeyFileCopy(db.id) },
            onOpenNativeManager = { db ->
                showDatabaseDetailSheet = false
                selectedDatabase = null
                DatabaseManagerNavigation.open(takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.KEEPASS, db.id))
            }
        )
    }
}

@Composable
internal fun remoteSyncStatusLabel(status: KeePassSyncStatus): String {
    return when (status) {
        KeePassSyncStatus.LOCAL_ONLY -> stringResource(R.string.keepass_remote_sync_status_local_only)
        KeePassSyncStatus.IN_SYNC -> stringResource(R.string.keepass_remote_sync_status_in_sync)
        KeePassSyncStatus.SYNCING -> stringResource(R.string.keepass_remote_sync_status_syncing)
        KeePassSyncStatus.PENDING_UPLOAD -> stringResource(R.string.keepass_remote_sync_status_pending_upload)
        KeePassSyncStatus.REMOTE_CHANGED -> stringResource(R.string.keepass_remote_sync_status_remote_changed)
        KeePassSyncStatus.CONFLICT -> stringResource(R.string.keepass_remote_sync_status_conflict)
        KeePassSyncStatus.FAILED -> stringResource(R.string.keepass_remote_sync_status_failed)
    }
}

@Composable
internal fun remoteSyncStatusColor(status: KeePassSyncStatus): Color {
    return when (status) {
        KeePassSyncStatus.IN_SYNC -> MaterialTheme.colorScheme.primary
        KeePassSyncStatus.SYNCING -> MaterialTheme.colorScheme.secondary
        KeePassSyncStatus.PENDING_UPLOAD -> MaterialTheme.colorScheme.tertiary
        KeePassSyncStatus.REMOTE_CHANGED,
        KeePassSyncStatus.CONFLICT,
        KeePassSyncStatus.FAILED -> MaterialTheme.colorScheme.error
        KeePassSyncStatus.LOCAL_ONLY -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun LocalKeePassDatabase.shouldShowRemoteSyncError(): Boolean {
    return lastSyncError?.isNotBlank() == true &&
        lastSyncStatus in setOf(KeePassSyncStatus.CONFLICT, KeePassSyncStatus.FAILED)
}

/**
 * 操作状态栏
 */
@Composable
private fun OperationStatusBar(state: LocalKeePassViewModel.OperationState) {
    val backgroundColor = when (state) {
        is LocalKeePassViewModel.OperationState.Loading -> MaterialTheme.colorScheme.primaryContainer
        is LocalKeePassViewModel.OperationState.Success -> MaterialTheme.colorScheme.tertiaryContainer
        is LocalKeePassViewModel.OperationState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    
    val contentColor = when (state) {
        is LocalKeePassViewModel.OperationState.Loading -> MaterialTheme.colorScheme.onPrimaryContainer
        is LocalKeePassViewModel.OperationState.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        is LocalKeePassViewModel.OperationState.Error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    val icon = when (state) {
        is LocalKeePassViewModel.OperationState.Loading -> Icons.Default.Sync
        is LocalKeePassViewModel.OperationState.Success -> Icons.Default.CheckCircle
        is LocalKeePassViewModel.OperationState.Error -> Icons.Default.Error
        else -> Icons.Default.Info
    }
    
    val message = when (state) {
        is LocalKeePassViewModel.OperationState.Loading -> state.message
        is LocalKeePassViewModel.OperationState.Success -> state.message
        is LocalKeePassViewModel.OperationState.Error -> state.message
        else -> ""
    }
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state is LocalKeePassViewModel.OperationState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

/**
 * 创建数据库 BottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateKeePassDatabaseBottomSheet(
    onDismiss: () -> Unit,
    onGenerateKeyFile: (Uri) -> Unit,
    onCreate: (
        name: String,
        password: String,
        location: KeePassStorageLocation,
        externalUri: Uri?,
        keyFileUri: Uri?,
        options: KeePassDatabaseCreationOptions,
        keepKeyFileCopy: Boolean,
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf(KeePassStorageLocation.INTERNAL) }
    var showPassword by remember { mutableStateOf(false) }
    var externalUri by remember { mutableStateOf<Uri?>(null) }

    var formatVersion by remember { mutableStateOf(KeePassFormatVersion.KDBX4) }
    var cipherAlgorithm by remember { mutableStateOf(KeePassCipherAlgorithm.AES) }
    var kdfAlgorithm by remember { mutableStateOf(KeePassKdfAlgorithm.ARGON2D) }
    var transformRounds by remember { mutableStateOf("8") }
    var memoryMb by remember {
        mutableStateOf((KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES / 1024L / 1024L).toString())
    }
    var parallelism by remember { mutableStateOf("2") }
    var showAdvancedCryptoOptions by remember { mutableStateOf(false) }
    
    // 密钥文件相关状态
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepKeyFileCopy by remember { mutableStateOf(false) }
    
    // 外部存储选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        externalUri = uri
    }
    
    // 密钥文件选择器
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }
    
    // 密钥文件生成器
    val createKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/xml")
    ) { uri: Uri? ->
        uri?.let {
            // 调用 ViewModel 生成文件内容
            onGenerateKeyFile(it)
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "new_keyfile.xml"
        }
    }

    val availableCipherOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassCipherAlgorithm.AES, KeePassCipherAlgorithm.TWOFISH)
        } else {
            listOf(
                KeePassCipherAlgorithm.AES,
                KeePassCipherAlgorithm.CHACHA20,
                KeePassCipherAlgorithm.TWOFISH
            )
        }
    }
    val availableKdfOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassKdfAlgorithm.AES_KDF)
        } else {
            listOf(
                KeePassKdfAlgorithm.ARGON2D,
                KeePassKdfAlgorithm.ARGON2ID,
                KeePassKdfAlgorithm.AES_KDF
            )
        }
    }

    LaunchedEffect(formatVersion) {
        if (cipherAlgorithm !in availableCipherOptions) {
            cipherAlgorithm = availableCipherOptions.first()
        }
        if (kdfAlgorithm !in availableKdfOptions) {
            kdfAlgorithm = availableKdfOptions.first()
        }
    }

    val roundsValue = transformRounds.toLongOrNull()
    val memoryMbValue = memoryMb.toLongOrNull()
    val parallelismValue = parallelism.toIntOrNull()
    val advancedOptionsValid = roundsValue != null && roundsValue > 0L &&
        (
            kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF ||
                ((memoryMbValue != null && memoryMbValue > 0L) &&
                    (parallelismValue != null && parallelismValue > 0))
            )

    val isValid = name.isNotBlank() &&
                  (storageLocation == KeePassStorageLocation.INTERNAL || externalUri != null) &&
                  (
                    (password.isNotBlank() && password == confirmPassword) ||
                    (useKeyFile && keyFileUri != null)
                  ) &&
                  advancedOptionsValid
    
    DatabaseManagementFormSheet(
        onDismiss = onDismiss,
        testTagPrefix = "keepass_create",
        actions = {
            Button(
                onClick = {
                    val options = KeePassDatabaseCreationOptions(
                        formatVersion = formatVersion,
                        cipherAlgorithm = cipherAlgorithm,
                        kdfAlgorithm = kdfAlgorithm,
                        transformRounds = roundsValue ?: 8L,
                        memoryBytes = ((memoryMbValue ?: 32L) * 1024L * 1024L),
                        parallelism = parallelismValue ?: 2
                    ).normalized()
                    onCreate(
                        name,
                        password,
                        storageLocation,
                        externalUri,
                        if (useKeyFile) keyFileUri else null,
                        options,
                        useKeyFile && keepKeyFileCopy,
                    )
                },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(
                    stringResource(R.string.create),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) {
            // 标题
            Text(
                stringResource(R.string.create_keepass_database),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // 表单区域
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 数据库名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.database_name)) },
                    placeholder = { Text(stringResource(R.string.database_name_placeholder)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) }
                )
                
                // 密码
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_password)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    visualTransformation = if (showPassword) 
                        VisualTransformation.None 
                    else 
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 确认密码
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.confirm_password)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    visualTransformation = if (showPassword) 
                        VisualTransformation.None 
                    else 
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    isError = confirmPassword.isNotBlank() && password != confirmPassword,
                    supportingText = if (confirmPassword.isNotBlank() && password != confirmPassword) {
                        { Text(stringResource(R.string.password_mismatch)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 安全设置区
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.local_keepass_security_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // 密钥文件开关
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { useKeyFile = !useKeyFile }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.local_keepass_use_key_file),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.local_keepass_use_key_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useKeyFile,
                            onCheckedChange = { useKeyFile = it }
                        )
                    }
                }
                
                // 密钥文件选择
                AnimatedVisibility(visible = useKeyFile) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = keyFileName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.local_keepass_key_file)) },
                            placeholder = { Text(stringResource(R.string.local_keepass_key_file_pick_or_generate)) },
                            shape = DatabaseManagementFieldShape,
                            leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { createKeyFileLauncher.launch("monica.key") }) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.local_keepass_generate_new_key_file)
                                        )
                                    }
                                    IconButton(onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = stringResource(R.string.local_keepass_select_existing_key_file)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) }
                        )
                        if (keyFileUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { keepKeyFileCopy = !keepKeyFileCopy }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = keepKeyFileCopy,
                                    onCheckedChange = { keepKeyFileCopy = it },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.local_keepass_keep_key_file_copy),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
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
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { showAdvancedCryptoOptions = !showAdvancedCryptoOptions }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.local_keepass_advanced_crypto_options),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (showAdvancedCryptoOptions) {
                                stringResource(R.string.collapse)
                            } else {
                                stringResource(R.string.expand)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (showAdvancedCryptoOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedVisibility(visible = showAdvancedCryptoOptions) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdbx_version),
                            selectedText = when (formatVersion) {
                                KeePassFormatVersion.KDBX3 -> stringResource(R.string.local_keepass_kdbx3)
                                KeePassFormatVersion.KDBX4 -> stringResource(R.string.local_keepass_kdbx4)
                            },
                            options = KeePassFormatVersion.entries,
                            optionLabel = { option ->
                                when (option) {
                                    KeePassFormatVersion.KDBX3 -> stringResource(R.string.local_keepass_kdbx3)
                                    KeePassFormatVersion.KDBX4 -> stringResource(R.string.local_keepass_kdbx4)
                                }
                            },
                            onSelected = { formatVersion = it }
                        )

                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_cipher_algorithm),
                            selectedText = cipherAlgorithm.toReadableLabel(),
                            options = availableCipherOptions,
                            optionLabel = { it.toReadableLabel() },
                            onSelected = { cipherAlgorithm = it }
                        )

                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdf_algorithm),
                            selectedText = kdfAlgorithm.toReadableLabel(),
                            options = availableKdfOptions,
                            optionLabel = { it.toReadableLabel() },
                            onSelected = { kdfAlgorithm = it }
                        )

                        OutlinedTextField(
                            value = transformRounds,
                            onValueChange = { transformRounds = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.local_keepass_transform_rounds)) },
                            singleLine = true,
                            shape = DatabaseManagementFieldShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        AnimatedVisibility(visible = kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = memoryMb,
                                    onValueChange = { memoryMb = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_memory_mb)) },
                                    singleLine = true,
                                    shape = DatabaseManagementFieldShape,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = parallelism,
                                    onValueChange = { parallelism = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_parallelism)) },
                                    singleLine = true,
                                    shape = DatabaseManagementFieldShape,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            
            // 存储位置选择
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.storage_location),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 内部存储卡片
                    StorageCard(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.internal_storage),
                        selected = storageLocation == KeePassStorageLocation.INTERNAL,
                        onClick = { storageLocation = KeePassStorageLocation.INTERNAL },
                        modifier = Modifier.weight(1f)
                    )
                    
                    // 外部存储卡片
                    StorageCard(
                        icon = Icons.Outlined.SdStorage,
                        title = stringResource(R.string.external_storage),
                        selected = storageLocation == KeePassStorageLocation.EXTERNAL,
                        onClick = { 
                            storageLocation = KeePassStorageLocation.EXTERNAL
                            if (externalUri == null) {
                                directoryPickerLauncher.launch(null)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // 外部存储路径显示
                AnimatedVisibility(visible = storageLocation == KeePassStorageLocation.EXTERNAL) {
                    Surface(
                        shape = DatabaseManagementFieldShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { directoryPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (externalUri != null)
                                    stringResource(R.string.location_selected)
                                else
                                    stringResource(R.string.select_location),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachWebDavDatabaseBottomSheet(
    viewModel: LocalKeePassViewModel,
    startWithCreate: Boolean,
    onDismiss: () -> Unit
) {
    KeepassWebDavBrowserBottomSheet(
        viewModel = viewModel,
        startWithCreate = startWithCreate,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachOneDriveDatabaseBottomSheet(
    viewModel: LocalKeePassViewModel,
    startWithCreate: Boolean,
    onDismiss: () -> Unit
) {
    KeepassOneDriveBrowserBottomSheet(
        viewModel = viewModel,
        startWithCreate = startWithCreate,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachGoogleDriveDatabaseBottomSheet(
    viewModel: LocalKeePassViewModel,
    startWithCreate: Boolean,
    onDismiss: () -> Unit
) {
    KeepassGoogleDriveBrowserBottomSheet(
        viewModel = viewModel,
        startWithCreate = startWithCreate,
        onDismiss = onDismiss
    )
}

/**
 * KeePass 参数下拉框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> KeepassOptionDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
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
private fun KeePassCipherAlgorithm.toReadableLabel(): String {
    return when (this) {
        KeePassCipherAlgorithm.AES -> stringResource(R.string.local_keepass_cipher_aes)
        KeePassCipherAlgorithm.CHACHA20 -> stringResource(R.string.local_keepass_cipher_chacha20)
        KeePassCipherAlgorithm.TWOFISH -> stringResource(R.string.local_keepass_cipher_twofish)
    }
}

@Composable
private fun KeePassKdfAlgorithm.toReadableLabel(): String {
    return when (this) {
        KeePassKdfAlgorithm.AES_KDF -> stringResource(R.string.local_keepass_kdf_aes)
        KeePassKdfAlgorithm.ARGON2D -> stringResource(R.string.local_keepass_kdf_argon2d)
        KeePassKdfAlgorithm.ARGON2ID -> stringResource(R.string.local_keepass_kdf_argon2id)
    }
}

/**
 * 存储位置选择卡片
 */
@Composable
private fun StorageCard(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) 
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary) 
        else 
            null,
        modifier = modifier.height(80.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 导入外部数据库对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportExternalDatabaseDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onImport: (name: String, password: String, keyFileUri: Uri?, keepKeyFileCopy: Boolean) -> Unit
) {
    val context = LocalContext.current
    val providerDisplayName by produceState<String?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            KeePassFileNameResolver.queryDisplayName(context.contentResolver, uri)
        }
    }

    var name by remember(uri) {
        mutableStateOf(
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = null,
                uriLastPathSegment = uri.lastPathSegment
            ) ?: KeePassFileNameResolver.DEFAULT_DATABASE_NAME
        )
    }
    var nameEdited by remember(uri) { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepKeyFileCopy by remember { mutableStateOf(false) }

    // URI 的末段可能只是 document:数字；拿到 DISPLAY_NAME 后再补上真实文件名，
    // 但不能覆盖用户已经手动修改过的名称。
    LaunchedEffect(providerDisplayName, nameEdited) {
        if (!nameEdited) {
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = providerDisplayName,
                uriLastPathSegment = uri.lastPathSegment
            )?.let { resolvedName -> name = resolvedName }
        }
    }
    
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri: Uri? ->
        selectedUri?.let {
            keyFileUri = it
            keyFileName = KeePassFileNameResolver.displayFileNameFromCandidates(
                displayName = KeePassFileNameResolver.queryDisplayName(context.contentResolver, it),
                uriLastPathSegment = it.lastPathSegment
            )
        }
    }
    
    val isValid = name.isNotBlank() && (password.isNotBlank() || keyFileUri != null)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.import_existing_database),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 文件位置提示
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.SdStorage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            KeePassFileNameResolver.displayFileNameFromCandidates(
                                displayName = providerDisplayName,
                                uriLastPathSegment = uri.lastPathSegment
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                // 数据库显示名称
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = name,
                    onValueChange = {
                        nameEdited = true
                        name = it
                    },
                    label = { Text(stringResource(R.string.database_name)) },
                    placeholder = { Text(stringResource(R.string.database_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 密码
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) 
                        VisualTransformation.None 
                    else 
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    supportingText = {
                        Text(stringResource(R.string.enter_database_password_hint))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = keyFileName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.local_keepass_key_file_optional)) },
                    placeholder = { Text(stringResource(R.string.local_keepass_key_file_tap_to_select)) },
                    trailingIcon = {
                        IconButton(onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.local_keepass_select_key_file)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) }
                )
                
                Text(
                    if (keyFileUri == null) {
                        stringResource(R.string.local_keepass_no_key_file_selected)
                    } else {
                        stringResource(R.string.local_keepass_key_file_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
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
                            Text(
                                stringResource(R.string.local_keepass_keep_key_file_copy),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onImport(name, password, keyFileUri, keepKeyFileCopy)
                },
                enabled = isValid
            ) {
                Text(stringResource(R.string.import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 存储位置选项
 */
@Composable
private fun StorageLocationOption(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            trailing?.invoke()
        }
    }
}

/**
 * 数据库详情底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeePassDatabaseDetailBottomSheet(
    database: LocalKeePassDatabase,
    verificationState: LocalKeePassViewModel.VerificationState,
    permissionState: KeePassUriPermissionState,
    keyFileAccessState: LocalKeePassViewModel.KeyFileAccessState,
    onDismiss: () -> Unit,
    onSetDefault: (LocalKeePassDatabase) -> Unit,
    onDelete: (LocalKeePassDatabase) -> Unit,
    onTransferToInternal: (LocalKeePassDatabase) -> Unit,
    onTransferToExternal: (LocalKeePassDatabase) -> Unit,
    onVerifyPassword: (LocalKeePassDatabase, String, Uri?) -> Unit,
    onSyncRemote: (LocalKeePassDatabase) -> Unit,
    onResolveConflict: (LocalKeePassDatabase) -> Unit,
    onExport: (LocalKeePassDatabase) -> Unit,
    onRepairPermission: (LocalKeePassDatabase) -> Unit,
    onKeepKeyFileCopy: (LocalKeePassDatabase) -> Unit,
    onExportKeyFileCopy: (LocalKeePassDatabase) -> Unit,
    onDeleteKeyFileCopy: (LocalKeePassDatabase) -> Unit,
    onOpenNativeManager: (LocalKeePassDatabase) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val creationOptions = database.toCreationOptions()
    val isRemoteDatabase = database.sourceType == KeePassDatabaseSourceType.REMOTE_WEBDAV ||
        database.sourceType == KeePassDatabaseSourceType.REMOTE_ONEDRIVE ||
        database.sourceType == KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteKeyFileCopyConfirm by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var verifyPassword by remember { mutableStateOf("") }
    var showVerifyPassword by remember { mutableStateOf(false) }
    var verifyKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var verifyKeyFileName by remember { mutableStateOf("") }
    val verifyKeyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri: Uri? ->
        selectedUri?.let {
            verifyKeyFileUri = it
            verifyKeyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    fun dismissSheet(afterDismiss: (() -> Unit)? = null) {
        coroutineScope.launch {
            if (sheetState.isVisible) {
                sheetState.hide()
            }
            onDismiss()
            afterDismiss?.invoke()
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = { dismissSheet() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("keepass_database_detail"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DatabaseManagementIconBadge(Icons.Default.Key)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(database.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(keePassSourceLabel(database.sourceType), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (database.isDefault) Icon(Icons.Default.Star, contentDescription = stringResource(R.string.default_label))
            }

            DatabaseManagementCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (verificationState is LocalKeePassViewModel.VerificationState.Verifying) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(if (verificationState is LocalKeePassViewModel.VerificationState.Failed) Icons.Default.Warning else Icons.Default.VerifiedUser,
                                contentDescription = null, tint = if (verificationState is LocalKeePassViewModel.VerificationState.Failed)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text(keePassVerificationLabel(verificationState), style = MaterialTheme.typography.titleSmall)
                    }
                    if (verificationState is LocalKeePassViewModel.VerificationState.Verified) {
                        Text(stringResource(R.string.local_keepass_decrypt_time_value, verificationState.decryptTimeMs),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (verificationState is LocalKeePassViewModel.VerificationState.Failed) {
                        Text(verificationState.message, color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { showVerifyDialog = true }) {
                            Text(stringResource(R.string.local_keepass_repair_credentials))
                        }
                    }
                }
            }

            Button(onClick = { dismissSheet { onOpenNativeManager(database) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("keepass_browse_database")) {
                Icon(Icons.Default.AccountTree, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.keepass_native_open_manager))
            }

            if (isRemoteDatabase) {
                val hasConflict = database.lastSyncStatus == KeePassSyncStatus.CONFLICT
                DatabaseManagementActionGroup(listOf(DatabaseManagementAction(
                    icon = if (hasConflict) Icons.Default.Merge else Icons.Default.Sync,
                    title = stringResource(if (hasConflict) R.string.keepass_conflict_review else R.string.sync_now),
                    subtitle = listOfNotNull(remoteSyncStatusLabel(database.lastSyncStatus),
                        database.lastSyncError?.takeIf { database.shouldShowRemoteSyncError() }).joinToString("\n"),
                    warning = hasConflict || database.lastSyncStatus == KeePassSyncStatus.FAILED,
                    enabled = database.lastSyncStatus != KeePassSyncStatus.SYNCING,
                    busy = database.lastSyncStatus == KeePassSyncStatus.SYNCING,
                    onClick = { dismissSheet { if (hasConflict) onResolveConflict(database) else onSyncRemote(database) } }
                )))
            }

            val permissionLabel = stringResource(when (permissionState) {
                KeePassUriPermissionState.READ_WRITE -> R.string.keepass_permission_read_write
                KeePassUriPermissionState.READ_ONLY -> R.string.keepass_permission_read_only
                KeePassUriPermissionState.MISSING -> R.string.keepass_permission_missing
            })
            if (!isRemoteDatabase && database.storageLocation == KeePassStorageLocation.EXTERNAL && permissionState != KeePassUriPermissionState.READ_WRITE) {
                DatabaseManagementActionGroup(listOf(DatabaseManagementAction(
                    Icons.Default.Lock, stringResource(R.string.keepass_file_permission_title),
                    { onRepairPermission(database) },
                    permissionLabel + "\n" + stringResource(R.string.keepass_file_permission_repair_hint), warning = true)))
            }

            val hasInternalKeyFile = !database.keyFileInternalPath.isNullOrBlank()
            if (!database.keyFileUri.isNullOrBlank() || hasInternalKeyFile) {
                val keyUnavailable = keyFileAccessState == LocalKeePassViewModel.KeyFileAccessState.UNAVAILABLE
                val keyStatus = stringResource(when (keyFileAccessState) {
                    LocalKeePassViewModel.KeyFileAccessState.CHECKING -> R.string.local_keepass_key_file_access_checking
                    LocalKeePassViewModel.KeyFileAccessState.AVAILABLE -> R.string.local_keepass_key_file_access_available
                    LocalKeePassViewModel.KeyFileAccessState.UNAVAILABLE -> R.string.local_keepass_key_file_access_unavailable
                })
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.local_keepass_key_file_access_title), style = MaterialTheme.typography.labelLarge)
                    Text(listOfNotNull(database.keyFileName?.takeIf(String::isNotBlank), keyStatus,
                        stringResource(if (hasInternalKeyFile) R.string.local_keepass_key_file_private_copy else R.string.local_keepass_key_file_external_source)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (keyUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (keyUnavailable) Text(stringResource(R.string.local_keepass_key_file_repair_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    DatabaseManagementActionGroup(buildList {
                        if (!hasInternalKeyFile && !database.keyFileUri.isNullOrBlank()) add(DatabaseManagementAction(
                            Icons.Default.ContentCopy, stringResource(R.string.local_keepass_keep_key_file_copy_action), { onKeepKeyFileCopy(database) }))
                        if (hasInternalKeyFile) {
                            add(DatabaseManagementAction(Icons.Default.FileDownload, stringResource(R.string.local_keepass_export_key_file_copy), { onExportKeyFileCopy(database) }))
                            add(DatabaseManagementAction(Icons.Default.Delete, stringResource(R.string.local_keepass_delete_key_file_copy), { showDeleteKeyFileCopyConfirm = true }, warning = true))
                        }
                    })
                }
            }

            DatabaseManagementActionGroup(title = stringResource(R.string.settings_data_management), actions = buildList {
                if (!database.isDefault) add(DatabaseManagementAction(Icons.Default.Star, stringResource(R.string.set_as_default),
                    { dismissSheet { onSetDefault(database) } }))
                if (!isRemoteDatabase && database.storageLocation == KeePassStorageLocation.INTERNAL) {
                    add(DatabaseManagementAction(Icons.Default.Upload, stringResource(R.string.export_to_external), { dismissSheet { onExport(database) } }))
                    add(DatabaseManagementAction(Icons.Default.DriveFileMove, stringResource(R.string.transfer_to_external), { dismissSheet { onTransferToExternal(database) } }))
                }
                if (!isRemoteDatabase && database.storageLocation == KeePassStorageLocation.EXTERNAL) {
                    add(DatabaseManagementAction(Icons.Default.MoveToInbox, stringResource(R.string.transfer_to_internal), { dismissSheet { onTransferToInternal(database) } }))
                }
            })

            DatabaseManagementExpandableSection(title = stringResource(R.string.advanced_options), icon = Icons.Default.Tune) {
                InfoRow(stringResource(R.string.created_at), dateFormat.format(Date(database.createdAt)))
                InfoRow(stringResource(R.string.last_accessed), dateFormat.format(Date(database.lastAccessedAt)))
                if (isRemoteDatabase) InfoRow(stringResource(R.string.keepass_remote_path), database.filePath)
                if (!isRemoteDatabase && database.storageLocation == KeePassStorageLocation.EXTERNAL) {
                    InfoRow(stringResource(R.string.keepass_file_permission_title), permissionLabel)
                }
                InfoRow(stringResource(R.string.local_keepass_kdbx_version), stringResource(when (creationOptions.formatVersion) {
                    KeePassFormatVersion.KDBX3 -> R.string.local_keepass_kdbx3
                    KeePassFormatVersion.KDBX4 -> R.string.local_keepass_kdbx4
                }))
                InfoRow(stringResource(R.string.local_keepass_cipher_algorithm), creationOptions.cipherAlgorithm.toReadableLabel())
                InfoRow(stringResource(R.string.local_keepass_kdf_algorithm), creationOptions.kdfAlgorithm.toReadableLabel())
                InfoRow(stringResource(R.string.local_keepass_transform_rounds), creationOptions.transformRounds.toString())
                if (creationOptions.kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
                    InfoRow(stringResource(R.string.local_keepass_kdf_memory_mb), stringResource(R.string.local_keepass_kdf_memory_mb_value, creationOptions.memoryBytes / 1024L / 1024L))
                    InfoRow(stringResource(R.string.local_keepass_kdf_parallelism), creationOptions.parallelism.toString())
                }
                database.description?.let { InfoRow(stringResource(R.string.description), it) }
            }

            DatabaseManagementActionGroup(buildList {
                if (verificationState !is LocalKeePassViewModel.VerificationState.Failed) add(DatabaseManagementAction(
                    Icons.Default.VerifiedUser, stringResource(R.string.local_keepass_repair_credentials), { showVerifyDialog = true }))
                add(DatabaseManagementAction(Icons.Default.Delete, stringResource(R.string.remove_database), { showDeleteConfirm = true }, warning = true))
            })
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.confirm_remove)) },
            text = { 
                Text(
                    if (isRemoteDatabase)
                        stringResource(R.string.confirm_remove_remote_description)
                    else if (database.storageLocation == KeePassStorageLocation.INTERNAL)
                        stringResource(R.string.confirm_remove_internal_description)
                    else
                        stringResource(R.string.confirm_remove_external_description)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        dismissSheet {
                            onDelete(database)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteKeyFileCopyConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteKeyFileCopyConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.local_keepass_delete_key_file_copy)) },
            text = { Text(stringResource(R.string.local_keepass_delete_key_file_copy_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteKeyFileCopyConfirm = false
                        onDeleteKeyFileCopy(database)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteKeyFileCopyConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showVerifyDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyDialog = false },
            title = { Text(stringResource(R.string.local_keepass_reverify_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.local_keepass_reverify_dialog_desc))
                    if (!database.keyFileUri.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.local_keepass_key_file_repair_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        shape = DatabaseManagementFieldShape,
                        value = verifyPassword,
                        onValueChange = { verifyPassword = it },
                        label = { Text(stringResource(R.string.database_password)) },
                        singleLine = true,
                        visualTransformation = if (showVerifyPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = keepassCredentialKeyboardOptions(),
                        trailingIcon = {
                            IconButton(onClick = { showVerifyPassword = !showVerifyPassword }) {
                                Icon(
                                    if (showVerifyPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        shape = DatabaseManagementFieldShape,
                        value = verifyKeyFileName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.local_keepass_key_file_optional)) },
                        placeholder = { Text(stringResource(R.string.local_keepass_key_file_tap_to_select)) },
                        trailingIcon = {
                            IconButton(onClick = { verifyKeyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = stringResource(R.string.local_keepass_select_key_file)
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { verifyKeyFilePickerLauncher.launch(arrayOf("*/*")) }
                    )
                    Text(
                        if (verifyKeyFileUri == null) {
                            stringResource(R.string.local_keepass_no_key_file_selected)
                        } else {
                            stringResource(R.string.local_keepass_key_file_selected)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onVerifyPassword(database, verifyPassword, verifyKeyFileUri)
                        showVerifyDialog = false
                        verifyPassword = ""
                        verifyKeyFileUri = null
                        verifyKeyFileName = ""
                    },
                    enabled = verifyPassword.isNotBlank() || verifyKeyFileUri != null
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showVerifyDialog = false
                    verifyPassword = ""
                    verifyKeyFileUri = null
                    verifyKeyFileName = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 信息行
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

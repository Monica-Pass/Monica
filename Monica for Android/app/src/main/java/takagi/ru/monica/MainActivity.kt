package takagi.ru.monica

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import takagi.ru.monica.ui.navigation.easyNotesScreenEnter
import takagi.ru.monica.ui.navigation.easyNotesScreenExit
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryManager
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.model.LOGIN_TYPE_BARCODE
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.isBarcodeEntry
import takagi.ru.monica.data.model.isSshKeyEntry
import takagi.ru.monica.external.ExternalTotpImportController
import takagi.ru.monica.external.ExternalTotpImportRequest
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.navigation.Screen
import takagi.ru.monica.data.dedup.DedupMergeService
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SensitiveFieldMigrationManager
import takagi.ru.monica.security.lock.MainAppAccessState
import takagi.ru.monica.security.lock.MainAppLockPolicy
import takagi.ru.monica.perf.PowerSaveModeController
import takagi.ru.monica.ui.SimpleMainScreen
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditBillingAddressScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.screens.AddEditTotpScreen
import takagi.ru.monica.ui.screens.AutofillBlockedFieldsScreen
import takagi.ru.monica.ui.screens.AutofillSaveBlockedTargetsScreen
import takagi.ru.monica.ui.screens.AutofillSettingsV2Screen
import takagi.ru.monica.ui.screens.BankCardDetailScreen
import takagi.ru.monica.ui.screens.BillingAddressDetailScreen
import takagi.ru.monica.ui.screens.BottomNavSettingsScreen
import takagi.ru.monica.ui.screens.ChangePasswordScreen
import takagi.ru.monica.ui.screens.DocumentDetailScreen
import takagi.ru.monica.ui.screens.ExportDataScreen
import takagi.ru.monica.ui.screens.ForgotPasswordScreen
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.screens.LoginScreen
import takagi.ru.monica.ui.screens.MasterPasswordLockingSettingsScreen
import takagi.ru.monica.ui.screens.QrScannerScreen
import takagi.ru.monica.ui.screens.QuickSetupScreen
import takagi.ru.monica.ui.screens.ResetPasswordScreen
import takagi.ru.monica.ui.screens.SecurityAnalysisScreen
import takagi.ru.monica.ui.screens.SecurityQuestionsSetupScreen
import takagi.ru.monica.ui.screens.SecurityQuestionsVerificationScreen
import takagi.ru.monica.ui.screens.SettingsScreen
import takagi.ru.monica.ui.screens.PermissionManagementScreen
import takagi.ru.monica.ui.screens.MonicaPlusScreen
import takagi.ru.monica.ui.screens.PaymentScreen
import takagi.ru.monica.ui.screens.SupportAuthorScreen
import takagi.ru.monica.ui.screens.OneDriveBackupScreen
import takagi.ru.monica.ui.screens.WebDavBackupScreen
import takagi.ru.monica.ui.screens.MdbxManagerScreen
import takagi.ru.monica.ui.screens.MdbxManagerInitialPage
import takagi.ru.monica.ui.screens.MdbxLocalCreateScreen
import takagi.ru.monica.ui.screens.MdbxLocalOpenScreen
import takagi.ru.monica.ui.screens.MdbxOneDriveCreateScreen
import takagi.ru.monica.ui.screens.MdbxOneDriveOpenScreen
import takagi.ru.monica.ui.screens.MdbxWebDavCreateScreen
import takagi.ru.monica.ui.screens.MdbxWebDavOpenScreen
import takagi.ru.monica.ui.screens.KeePassKdbxViewModel
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.ui.scale.ProvideMonicaInterfaceScale
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.steam.ui.SteamQrScannerScreen
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.DedupEngineViewModel
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SecurityAnalysisViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TotpCategoryFilter
import takagi.ru.monica.viewmodel.TotpViewModel
import androidx.compose.foundation.isSystemInDarkTheme
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.utils.ScreenshotProtection
import takagi.ru.monica.ui.base.BaseMonicaActivity
import takagi.ru.monica.security.SessionManager
import androidx.compose.runtime.collectAsState
import takagi.ru.monica.util.FileOperationHelper
import takagi.ru.monica.util.PhotoPickerHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.AutoBackupManager

private val PLUS_ONLY_COLOR_SCHEMES = setOf(
    ColorScheme.WATER_LILIES,
    ColorScheme.IMPRESSION_SUNRISE,
    ColorScheme.JAPANESE_BRIDGE,
    ColorScheme.HAYSTACKS,
    ColorScheme.ROUEN_CATHEDRAL,
    ColorScheme.PARLIAMENT_FOG,
    ColorScheme.CATPPUCCIN_LATTE,
    ColorScheme.CATPPUCCIN_FRAPPE,
    ColorScheme.CATPPUCCIN_MACCHIATO,
    ColorScheme.CATPPUCCIN_MOCHA,
)

private fun effectiveColorSchemeForPlusAccess(settings: AppSettings): ColorScheme {
    return if (!settings.isPlusActivated && settings.colorScheme in PLUS_ONLY_COLOR_SCHEMES) {
        ColorScheme.DEFAULT
    } else {
        settings.colorScheme
    }
}

private data class PendingAddStorageDefaults(
    val categoryId: Long? = null,
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val mdbxDatabaseId: Long? = null,
    val mdbxFolderId: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null
)

private data class PendingSendDraft(
    val title: String? = null,
    val text: String? = null,
    val notes: String? = null
)

private const val KEY_PENDING_ADD_CATEGORY_ID = "pending_add_category_id"
private const val KEY_PENDING_ADD_KEEPASS_DATABASE_ID = "pending_add_keepass_database_id"
private const val KEY_PENDING_ADD_KEEPASS_GROUP_PATH = "pending_add_keepass_group_path"
private const val KEY_PENDING_ADD_MDBX_DATABASE_ID = "pending_add_mdbx_database_id"
private const val KEY_PENDING_ADD_MDBX_FOLDER_ID = "pending_add_mdbx_folder_id"
private const val KEY_PENDING_ADD_BITWARDEN_VAULT_ID = "pending_add_bitwarden_vault_id"
private const val KEY_PENDING_ADD_BITWARDEN_FOLDER_ID = "pending_add_bitwarden_folder_id"
private const val KEY_PENDING_SEND_TITLE = "pending_send_title"
private const val KEY_PENDING_SEND_TEXT = "pending_send_text"
private const val KEY_PENDING_SEND_NOTES = "pending_send_notes"

private fun PendingAddStorageDefaults.hasAnyValue(): Boolean {
    return categoryId != null ||
        keepassDatabaseId != null ||
        !keepassGroupPath.isNullOrBlank() ||
        mdbxDatabaseId != null ||
        !mdbxFolderId.isNullOrBlank() ||
        bitwardenVaultId != null ||
        !bitwardenFolderId.isNullOrBlank()
}

private fun PendingSendDraft.hasAnyValue(): Boolean {
    return !title.isNullOrBlank() ||
        !text.isNullOrBlank() ||
        !notes.isNullOrBlank()
}

private fun SavedStateHandle.clearPendingAddStorageDefaults() {
    remove<Long>(KEY_PENDING_ADD_CATEGORY_ID)
    remove<Long>(KEY_PENDING_ADD_KEEPASS_DATABASE_ID)
    remove<String>(KEY_PENDING_ADD_KEEPASS_GROUP_PATH)
    remove<Long>(KEY_PENDING_ADD_MDBX_DATABASE_ID)
    remove<String>(KEY_PENDING_ADD_MDBX_FOLDER_ID)
    remove<Long>(KEY_PENDING_ADD_BITWARDEN_VAULT_ID)
    remove<String>(KEY_PENDING_ADD_BITWARDEN_FOLDER_ID)
}

private fun SavedStateHandle.clearPendingSendDraft() {
    remove<String>(KEY_PENDING_SEND_TITLE)
    remove<String>(KEY_PENDING_SEND_TEXT)
    remove<String>(KEY_PENDING_SEND_NOTES)
}

private fun SavedStateHandle.setPendingAddStorageDefaults(defaults: PendingAddStorageDefaults?) {
    if (defaults == null || !defaults.hasAnyValue()) {
        clearPendingAddStorageDefaults()
        return
    }

    if (defaults.categoryId != null) {
        set(KEY_PENDING_ADD_CATEGORY_ID, defaults.categoryId)
    } else {
        remove<Long>(KEY_PENDING_ADD_CATEGORY_ID)
    }
    if (defaults.keepassDatabaseId != null) {
        set(KEY_PENDING_ADD_KEEPASS_DATABASE_ID, defaults.keepassDatabaseId)
    } else {
        remove<Long>(KEY_PENDING_ADD_KEEPASS_DATABASE_ID)
    }
    val keepassGroupPath = defaults.keepassGroupPath?.takeIf { it.isNotBlank() }
    if (keepassGroupPath != null) {
        set(KEY_PENDING_ADD_KEEPASS_GROUP_PATH, keepassGroupPath)
    } else {
        remove<String>(KEY_PENDING_ADD_KEEPASS_GROUP_PATH)
    }
    if (defaults.mdbxDatabaseId != null) {
        set(KEY_PENDING_ADD_MDBX_DATABASE_ID, defaults.mdbxDatabaseId)
    } else {
        remove<Long>(KEY_PENDING_ADD_MDBX_DATABASE_ID)
    }
    val mdbxFolderId = defaults.mdbxFolderId?.takeIf { it.isNotBlank() }
    if (mdbxFolderId != null) {
        set(KEY_PENDING_ADD_MDBX_FOLDER_ID, mdbxFolderId)
    } else {
        remove<String>(KEY_PENDING_ADD_MDBX_FOLDER_ID)
    }
    if (defaults.bitwardenVaultId != null) {
        set(KEY_PENDING_ADD_BITWARDEN_VAULT_ID, defaults.bitwardenVaultId)
    } else {
        remove<Long>(KEY_PENDING_ADD_BITWARDEN_VAULT_ID)
    }
    val bitwardenFolderId = defaults.bitwardenFolderId?.takeIf { it.isNotBlank() }
    if (bitwardenFolderId != null) {
        set(KEY_PENDING_ADD_BITWARDEN_FOLDER_ID, bitwardenFolderId)
    } else {
        remove<String>(KEY_PENDING_ADD_BITWARDEN_FOLDER_ID)
    }
}

private fun SavedStateHandle.setPendingSendDraft(draft: PendingSendDraft?) {
    if (draft == null || !draft.hasAnyValue()) {
        clearPendingSendDraft()
        return
    }

    val title = draft.title?.takeIf { it.isNotBlank() }
    if (title != null) {
        set(KEY_PENDING_SEND_TITLE, title)
    } else {
        remove<String>(KEY_PENDING_SEND_TITLE)
    }
    val text = draft.text?.takeIf { it.isNotBlank() }
    if (text != null) {
        set(KEY_PENDING_SEND_TEXT, text)
    } else {
        remove<String>(KEY_PENDING_SEND_TEXT)
    }
    val notes = draft.notes?.takeIf { it.isNotBlank() }
    if (notes != null) {
        set(KEY_PENDING_SEND_NOTES, notes)
    } else {
        remove<String>(KEY_PENDING_SEND_NOTES)
    }
}

private fun SavedStateHandle.consumePendingAddStorageDefaults(): PendingAddStorageDefaults? {
    val defaults = PendingAddStorageDefaults(
        categoryId = get<Long>(KEY_PENDING_ADD_CATEGORY_ID),
        keepassDatabaseId = get<Long>(KEY_PENDING_ADD_KEEPASS_DATABASE_ID),
        keepassGroupPath = get<String>(KEY_PENDING_ADD_KEEPASS_GROUP_PATH)?.takeIf { it.isNotBlank() },
        mdbxDatabaseId = get<Long>(KEY_PENDING_ADD_MDBX_DATABASE_ID),
        mdbxFolderId = get<String>(KEY_PENDING_ADD_MDBX_FOLDER_ID)?.takeIf { it.isNotBlank() },
        bitwardenVaultId = get<Long>(KEY_PENDING_ADD_BITWARDEN_VAULT_ID),
        bitwardenFolderId = get<String>(KEY_PENDING_ADD_BITWARDEN_FOLDER_ID)?.takeIf { it.isNotBlank() }
    )
    clearPendingAddStorageDefaults()
    return defaults.takeIf { it.hasAnyValue() }
}

private fun SavedStateHandle.consumePendingSendDraft(): PendingSendDraft? {
    val draft = PendingSendDraft(
        title = get<String>(KEY_PENDING_SEND_TITLE)?.takeIf { it.isNotBlank() },
        text = get<String>(KEY_PENDING_SEND_TEXT)?.takeIf { it.isNotBlank() },
        notes = get<String>(KEY_PENDING_SEND_NOTES)?.takeIf { it.isNotBlank() }
    )
    clearPendingSendDraft()
    return draft.takeIf { it.hasAnyValue() }
}

@Composable
private fun AnimatedContentScope.AddEditRouteContent(
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    androidx.compose.runtime.CompositionLocalProvider(
        takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = easyNotesScreenEnter(),
            exit = ExitTransition.None
        ) {
            content()
        }
    }
}

class MainActivity : BaseMonicaActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private val externalTotpImportController = ExternalTotpImportController()

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_BACKUP_PREFS_NAME = "webdav_config"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val AUTO_BACKUP_INIT_DELAY_MS = 1500L
        private const val AUTO_BACKUP_INTERVAL_HOURS = 12L
    }

    // attachBaseContext 已由 BaseMonicaActivity 统一处理（语言、超时保护）

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState) // BaseMonicaActivity 已调用 enableEdgeToEdge()
        handleExternalTotpIntent(intent)

        // 注意：enableEdgeToEdge() 已在基类调用，这里不再重复

        // Initialize dependencies
        val database = PasswordDatabase.getDatabase(this)
        val securityManager = SecurityManager(this)
        val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(
            context = applicationContext,
            database = database,
            securityManager = securityManager
        )
        val repository = PasswordRepository(
            database.passwordEntryDao(), 
            database.categoryDao(),
            database.bitwardenFolderDao(),
            database.secureItemDao(),
            database.passkeyDao(),
            database.passwordArchiveSyncMetaDao(),
            database.passwordHistoryDao(),
            mdbxRepository = mdbxRepository
        )
        val secureItemRepository = takagi.ru.monica.repository.SecureItemRepository(
            database.secureItemDao(),
            mdbxRepository,
            securityManager::decryptDataIfMonicaCiphertext,
            takagi.ru.monica.attachments.repository.AttachmentRepository(database.attachmentDao())
        )
        val settingsManager = SettingsManager(this)
        
        // Initialize OperationLogger for timeline tracking
        takagi.ru.monica.utils.OperationLogger.init(this)
        
        // Initialize auto backup if enabled (deferred to reduce cold-start contention)
        initializeAutoBackupDeferred()

        // Notification Validator is temporarily disabled for stability.
        lifecycleScope.launch {
            settingsManager.updateNotificationValidatorEnabled(false)
            settingsManager.updateNotificationValidatorAutoMatch(false)
            settingsManager.updateNotificationValidatorId(-1L)
            val intent = Intent(this@MainActivity, takagi.ru.monica.service.NotificationValidatorService::class.java)
            stopService(intent)
        }

        setContent {
            MonicaApp(
                repository = repository,
                secureItemRepository = secureItemRepository,
                securityManager = securityManager,
                settingsManager = settingsManager,
                database = database,
                mdbxRepository = mdbxRepository,
                externalTotpImportController = externalTotpImportController
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalTotpIntent(intent)
    }

    private fun handleExternalTotpIntent(incomingIntent: Intent?) {
        if (!ExternalTotpImportController.isExternalOtpAuthIntent(incomingIntent)) return
        val accepted = externalTotpImportController.offer(incomingIntent)
        incomingIntent?.data = null
        if (!accepted) {
            Toast.makeText(this, getString(R.string.qr_invalid_authenticator), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 初始化自动备份
     * 检查是否需要执行备份(每天首次打开时,如果距离上次备份超过24小时)
     */
    private fun initializeAutoBackupDeferred() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                delay(AUTO_BACKUP_INIT_DELAY_MS)
                val prefs = getSharedPreferences(AUTO_BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
                val autoBackupEnabled = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
                if (!autoBackupEnabled) return@launch

                val lastBackupTime = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
                val currentTime = System.currentTimeMillis()
                if (shouldTriggerAutoBackup(lastBackupTime, currentTime)) {
                    Log.d(TAG, "Auto backup needed, triggering backup...")
                    AutoBackupManager(this@MainActivity).triggerBackupNow()
                } else {
                    Log.d(TAG, "Auto backup not needed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto backup init skipped: ${e.message}")
            }
        }
    }

    private fun shouldTriggerAutoBackup(lastBackupTime: Long, currentTime: Long): Boolean {
        if (lastBackupTime == 0L) return true

        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = lastBackupTime
        val lastBackupDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val lastBackupYear = calendar.get(java.util.Calendar.YEAR)

        calendar.timeInMillis = currentTime
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val currentYear = calendar.get(java.util.Calendar.YEAR)

        val isNewDay = (currentYear > lastBackupYear) ||
            (currentYear == lastBackupYear && currentDay > lastBackupDay)
        if (isNewDay) return true

        val hoursSinceLastBackup = (currentTime - lastBackupTime) / (1000 * 60 * 60)
        return hoursSinceLastBackup >= AUTO_BACKUP_INTERVAL_HOURS
    }

    // enableEdgeToEdge() 已由 BaseMonicaActivity 统一处理

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // 处理照片选择器的权限请求结果
        if (PhotoPickerHelper.handlePermissionResult(requestCode, permissions, grantResults)) return
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 处理文件导出/导入结果
        FileOperationHelper.handleExportResult(requestCode, resultCode, data)
        FileOperationHelper.handleImportResult(requestCode, resultCode, data)

        // 处理照片选择结果
        if (PhotoPickerHelper.handleCameraResult(requestCode, resultCode, data)) return
        if (PhotoPickerHelper.handleGalleryResult(requestCode, resultCode, data)) return
    }
}

@Composable
fun MonicaApp(
    repository: PasswordRepository,
    secureItemRepository: takagi.ru.monica.repository.SecureItemRepository,
    securityManager: SecurityManager,
    settingsManager: SettingsManager,
    database: PasswordDatabase,
    mdbxRepository: MdbxRepository,
    externalTotpImportController: ExternalTotpImportController
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val navController = rememberNavController()
    val pendingExternalTotpImport by externalTotpImportController.pendingRequest.collectAsState()

    // 创建权限共享 launcher
    var pendingSupportPermissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val sharedSupportPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingSupportPermissionCallback?.invoke(granted)
        pendingSupportPermissionCallback = null
    }

    val viewModel: PasswordViewModel = viewModel {
        val customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(database.customFieldDao())
        PasswordViewModel(
            repository,
            securityManager,
            secureItemRepository,
            customFieldRepository,
            navController.context,
            database.localKeePassDatabaseDao()
        )
    }
    val totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel = viewModel {
        takagi.ru.monica.viewmodel.TotpViewModel(
            secureItemRepository,
            repository,
            navController.context,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val bankCardViewModel: takagi.ru.monica.viewmodel.BankCardViewModel = viewModel {
        takagi.ru.monica.viewmodel.BankCardViewModel(
            secureItemRepository,
            navController.context,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val documentViewModel: takagi.ru.monica.viewmodel.DocumentViewModel = viewModel {
        takagi.ru.monica.viewmodel.DocumentViewModel(
            secureItemRepository,
            navController.context,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val billingAddressViewModel: BillingAddressViewModel = viewModel {
        BillingAddressViewModel(
            secureItemRepository,
            securityManager,
            navController.context.applicationContext
        )
    }
    val passwordHistoryManager = remember { PasswordHistoryManager(navController.context) }
    val generatorPreferencesManager = remember { takagi.ru.monica.data.GeneratorPreferencesManager(navController.context) }
    val settingsViewModel: SettingsViewModel = viewModel {
        SettingsViewModel(settingsManager, secureItemRepository)
    }
    val generatorViewModel: GeneratorViewModel = viewModel {
        GeneratorViewModel(generatorPreferencesManager)
    }
    val noteViewModel: takagi.ru.monica.viewmodel.NoteViewModel = viewModel {
        takagi.ru.monica.viewmodel.NoteViewModel(
            secureItemRepository,
            repository,
            navController.context,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = viewModel(
        viewModelStoreOwner = activity
    )
    
    // Passkey 通行密钥
    val passkeyRepository = remember {
        takagi.ru.monica.repository.PasskeyRepository(
            database.passkeyDao(),
            mdbxRepository,
            context.applicationContext
        )
    }
    val passkeyViewModel: takagi.ru.monica.viewmodel.PasskeyViewModel = viewModel {
        takagi.ru.monica.viewmodel.PasskeyViewModel(
            repository = passkeyRepository,
            context = navController.context,
            localKeePassDatabaseDao = database.localKeePassDatabaseDao(),
            securityManager = securityManager
        )
    }
    
    // KeePass KDBX 导出/导入
    val keePassViewModel = remember { KeePassKdbxViewModel() }
    
    // 本地 KeePass 数据库管理
    val localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel = viewModel {
        takagi.ru.monica.viewmodel.LocalKeePassViewModel(
            context.applicationContext as android.app.Application,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }

    // MDBX 数据库管理
    val mdbxViewModel: takagi.ru.monica.viewmodel.MdbxViewModel = viewModel {
        takagi.ru.monica.viewmodel.MdbxViewModel(
            context.applicationContext as android.app.Application,
            database.localMdbxDatabaseDao(),
            database.mdbxRemoteSourceDao(),
            database.passwordEntryDao(),
            database.secureItemDao(),
            database.passkeyDao(),
            database.attachmentDao(),
            database.customFieldDao(),
            securityManager
        )
    }

    var startupAuthState by remember { mutableStateOf<MainAppAccessState?>(null) }
    LaunchedEffect(passkeyRepository) {
        withContext(Dispatchers.IO) {
            runCatching { passkeyRepository.protectPlaintextPrivateKeys() }
        }
    }

    LaunchedEffect(viewModel, settingsManager) {
        val loadedState = withContext(Dispatchers.IO) {
            val settingsSnapshot = runCatching {
                settingsManager.settingsFlow.first()
            }.getOrElse { AppSettings() }
            runCatching {
                SessionManager.updateAutoLockTimeout(settingsSnapshot.autoLockMinutes)
                if (settingsSnapshot.disablePasswordVerification) {
                    securityManager.unlockVaultForDeveloperBypass(settingsSnapshot.autoLockMinutes)
                }
                MainAppLockPolicy.resolveAccessState(
                    securityManager,
                    context.applicationContext,
                    settingsSnapshot.autoLockMinutes,
                    settingsSnapshot.disablePasswordVerification
                )
            }.getOrElse {
                MainAppAccessState(
                    isFirstTime = false,
                    bypassEnabled = false,
                    canRestoreSession = false,
                    reason = "startup_load_error"
                )
            }
        }
        if (loadedState.bypassEnabled) {
            android.util.Log.w(
                "MonicaAuthGate",
                "Startup allowed via ${loadedState.reason}"
            )
            viewModel.markAuthenticatedForBypass()
        } else if (loadedState.canRestoreSession) {
            android.util.Log.d(
                "MonicaAuthGate",
                "Startup restored via ${loadedState.reason}"
            )
            viewModel.restoreAuthenticatedUiState()
        } else {
            android.util.Log.d(
                "MonicaAuthGate",
                "Startup requires authentication via ${loadedState.reason}"
            )
        }
        startupAuthState = loadedState
    }

    val settings by settingsViewModel.settings.collectAsState()
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val powerSaveController = remember(context) { PowerSaveModeController(context) }
    val powerSavePolicy by powerSaveController.policy.collectAsState()
    DisposableEffect(powerSaveController) {
        onDispose { powerSaveController.close() }
    }

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    ProvideMonicaInterfaceScale(settings.interfaceScalePercent) {
        MonicaTheme(
            darkTheme = darkTheme,
            oledPureBlackEnabled = settings.oledPureBlackEnabled,
            colorScheme = effectiveColorSchemeForPlusAccess(settings),
            customPrimaryColor = settings.customPrimaryColor,
            customSecondaryColor = settings.customSecondaryColor,
            customTertiaryColor = settings.customTertiaryColor,
            customNeutralColor = settings.customNeutralColor,
            customNeutralVariantColor = settings.customNeutralVariantColor
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.theme.LocalPowerSavePolicy provides powerSavePolicy
            ) {
            // 应用防截屏保护
            ScreenshotProtection(enabled = settings.screenshotProtectionEnabled)

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                val authState = startupAuthState
                if (authState == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    MonicaContent(
                        navController = navController,
                        viewModel = viewModel,
                        totpViewModel = totpViewModel,
                        bankCardViewModel = bankCardViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        settingsViewModel = settingsViewModel,
                        generatorViewModel = generatorViewModel,
                        noteViewModel = noteViewModel,
                        bitwardenViewModel = bitwardenViewModel,
                        passkeyViewModel = passkeyViewModel,
                        keePassViewModel = keePassViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        mdbxViewModel = mdbxViewModel,
                        mdbxRepository = mdbxRepository,
                        securityManager = securityManager,
                        repository = repository,
                        database = database,
                        secureItemRepository = secureItemRepository,
                        passwordHistoryManager = passwordHistoryManager,
                        pendingExternalTotpImport = pendingExternalTotpImport,
                        onConsumeExternalTotpImport = externalTotpImportController::consume,
                        initialAuthState = authState,
                        onPermissionRequested = { permission, callback ->
                            pendingSupportPermissionCallback = callback
                            sharedSupportPermissionLauncher.launch(permission)
                        }
                    )
                }
            }
            }
        }
    }
}

@Composable
fun MonicaContent(
    navController: androidx.navigation.NavHostController,
    viewModel: PasswordViewModel,
    totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    bankCardViewModel: takagi.ru.monica.viewmodel.BankCardViewModel,
    documentViewModel: takagi.ru.monica.viewmodel.DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    settingsViewModel: SettingsViewModel,
    generatorViewModel: GeneratorViewModel,
    noteViewModel: takagi.ru.monica.viewmodel.NoteViewModel,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel,
    passkeyViewModel: takagi.ru.monica.viewmodel.PasskeyViewModel,
    keePassViewModel: KeePassKdbxViewModel,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    mdbxViewModel: takagi.ru.monica.viewmodel.MdbxViewModel,
    mdbxRepository: MdbxRepository,
    securityManager: SecurityManager,
    repository: PasswordRepository,
    database: PasswordDatabase,
    secureItemRepository: SecureItemRepository,
    passwordHistoryManager: PasswordHistoryManager,
    pendingExternalTotpImport: ExternalTotpImportRequest?,
    onConsumeExternalTotpImport: (Long) -> Unit,
    initialAuthState: MainAppAccessState =
        MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = false,
            reason = "default"
        ),
    onPermissionRequested: (String, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(localKeePassViewModel, navController) {
        localKeePassViewModel.nativeManagerOpenRequests.collect {
            if (navController.currentDestination?.route != Screen.LocalKeePass.route) {
                navController.navigate(Screen.LocalKeePass.route) {
                    launchSingleTop = true
                }
            }
        }
    }
    val sensitiveFieldMigrationManager = remember(database, securityManager) {
        SensitiveFieldMigrationManager(
            context = context.applicationContext,
            database = database,
            securityManager = securityManager
        )
    }

    val isFirstTime = initialAuthState.isFirstTime
    val currentRoute = navBackStackEntry?.destination?.route
    var quickSetupDismissedThisSession by remember { mutableStateOf(false) }
    val authRouteSet = remember {
        setOf(
            Screen.Login.route,
            Screen.ForgotPassword.route,
            Screen.ResetPassword.route,
            Screen.SecurityQuestionsSetup.route,
            Screen.SecurityQuestionsVerification.route
        )
    }
    var hasRenderedLoginFirstFrame by remember { mutableStateOf(false) }
    val authenticatedAccessState = remember {
        MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = true,
            reason = "already_authenticated"
        )
    }
    val mainAppAccessState = remember(
        isAuthenticated,
        settings.autoLockMinutes,
        settings.disablePasswordVerification,
        initialAuthState
    ) {
        if (isAuthenticated) {
            authenticatedAccessState
        } else {
            MainAppLockPolicy.resolveAccessState(
                securityManager,
                context.applicationContext,
                settings.autoLockMinutes,
                settings.disablePasswordVerification
            )
        }
    }
    val shouldRequireAuthentication = !isAuthenticated && !mainAppAccessState.canEnterMainApp
    val isOnAuthRoute = currentRoute != null && currentRoute in authRouteSet
    val showAuthTransitionGate = shouldRequireAuthentication && (
        !isOnAuthRoute ||
            (currentRoute == Screen.Login.route && !hasRenderedLoginFirstFrame)
        )

    LaunchedEffect(shouldRequireAuthentication, currentRoute) {
        if (!shouldRequireAuthentication || currentRoute != Screen.Login.route) {
            hasRenderedLoginFirstFrame = false
        }
    }
    
    // 使用 rememberUpdatedState 确保生命周期观察者闭包始终访问最新值
    val currentIsAuthenticated by rememberUpdatedState(isAuthenticated)
    val currentSettings by rememberUpdatedState(settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val accessState = MainAppLockPolicy.resolveAccessState(
                        securityManager,
                        context.applicationContext,
                        currentSettings.autoLockMinutes,
                        currentSettings.disablePasswordVerification
                    )

                    if (!currentIsAuthenticated && accessState.canEnterMainApp) {
                        android.util.Log.d(
                            "MonicaAuthGate",
                            "ON_START allowed while unauthenticated via ${accessState.reason}"
                        )
                        if (accessState.bypassEnabled) {
                            securityManager.unlockVaultForDeveloperBypass(currentSettings.autoLockMinutes)
                            viewModel.markAuthenticatedForBypass()
                        } else if (accessState.canRestoreSession) {
                            viewModel.restoreAuthenticatedUiState()
                        }
                    }

                    if (currentIsAuthenticated && !accessState.canEnterMainApp) {
                        android.util.Log.w(
                            "MonicaAuthGate",
                            "ON_START revoked authenticated UI state via ${accessState.reason}"
                        )
                        viewModel.logout()
                        return@LifecycleEventObserver
                    }

                    if (currentIsAuthenticated || accessState.bypassEnabled) {
                        viewModel.refreshKeePassFromSourceForCurrentContext()
                    }
                    if (!currentIsAuthenticated && !accessState.canEnterMainApp) {
                        android.util.Log.d(
                            "MonicaAuthGate",
                            "ON_START navigating to auth via ${accessState.reason}"
                        )
                        navController.navigate(Screen.Login.route) {
                            launchSingleTop = true
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 使用固定的 startDestination 避免竞态条件
    // 认证状态变化时通过 LaunchedEffect 处理导航
    val fixedStartDestination = remember(initialAuthState.canEnterMainApp) {
        if (initialAuthState.canEnterMainApp) {
            Screen.Main.createRoute()
        } else {
            Screen.Login.route
        }
    }
    
    // 当认证状态变化时处理导航
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.Login.route) {
                navController.navigate(Screen.Main.createRoute()) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            }
            launch {
                delay(1_200L)
                bitwardenViewModel.requestStartupAutoSync()
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    delay(15_000)
                    sensitiveFieldMigrationManager.runUnlockedSmallBatch()
                }.onFailure { error ->
                    Log.w(
                        "SensitiveMigration",
                        "Unlocked sensitive field migration failed: ${error.javaClass.simpleName}"
                    )
                }
            }
        } else {
            if (mainAppAccessState.bypassEnabled) {
                android.util.Log.w(
                    "MonicaAuthGate",
                    "Auth effect allowed via ${mainAppAccessState.reason}"
                )
                viewModel.markAuthenticatedForBypass()
                return@LaunchedEffect
            }
            if (mainAppAccessState.canRestoreSession) {
                android.util.Log.d(
                    "MonicaAuthGate",
                    "Auth effect restored via ${mainAppAccessState.reason}"
                )
                viewModel.restoreAuthenticatedUiState()
                return@LaunchedEffect
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@LaunchedEffect
            }
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != Screen.Login.route) {
                navController.navigate(Screen.Login.route) {
                    launchSingleTop = true
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    var lastExternalTotpNavigationId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        pendingExternalTotpImport?.id,
        isAuthenticated,
        shouldRequireAuthentication,
        currentRoute
    ) {
        val request = pendingExternalTotpImport ?: return@LaunchedEffect
        if (!isAuthenticated || shouldRequireAuthentication || isOnAuthRoute) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@LaunchedEffect
        }
        if (lastExternalTotpNavigationId == request.id) return@LaunchedEffect

        lastExternalTotpNavigationId = request.id
        navController.navigate(Screen.AddEditTotp.createRoute())
    }

    LaunchedEffect(settings.quickSetupCompleted, shouldRequireAuthentication, currentRoute) {
        if (
            !settings.quickSetupCompleted &&
            isFirstTime &&
            !quickSetupDismissedThisSession &&
            !shouldRequireAuthentication &&
            currentRoute == Screen.Main.routePattern
        ) {
            navController.navigate(Screen.QuickSetup.route) {
                launchSingleTop = true
            }
        }
    }

    // Emergency safe mode: disable global shared transition lookahead to avoid
    // "Placement happened before lookahead" crashes on affected devices/builds.
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    androidx.compose.runtime.CompositionLocalProvider(
        takagi.ru.monica.ui.LocalSharedTransitionScope provides null,
        takagi.ru.monica.ui.LocalReduceAnimations provides true,
        takagi.ru.monica.ui.LocalHapticFeedbackEnabled provides settings.hapticFeedbackEnabled
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = fixedStartDestination
            ) {
            composable(
                route = Screen.Login.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                LoginScreen(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    onFirstFrameRendered = {
                        hasRenderedLoginFirstFrame = true
                    },
                    onForgotPassword = {
                        navController.navigate(Screen.ForgotPassword.route)
                    }
                )
            }

        composable(
            route = Screen.Main.routePattern,
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            ),
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() }
        ) { backStackEntry ->
            val tab = backStackEntry.arguments?.getInt("tab") ?: 0
            val scope = rememberCoroutineScope()
            val mainQrResult = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.get<String>("qr_result")
            val steamQrResult = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.get<String>("steam_qr_result")
            val steamQrAccountId = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.get<Long>("steam_qr_account_id")

            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            // V1 经典本地密码库界面
            SimpleMainScreen(
                passwordViewModel = viewModel,
                settingsViewModel = settingsViewModel,
                totpViewModel = totpViewModel,
                bankCardViewModel = bankCardViewModel,
                documentViewModel = documentViewModel,
                billingAddressViewModel = billingAddressViewModel,
                generatorViewModel = generatorViewModel,
                noteViewModel = noteViewModel,
                bitwardenViewModel = bitwardenViewModel,
                passkeyViewModel = passkeyViewModel,
                localKeePassViewModel = localKeePassViewModel,
                mdbxViewModel = mdbxViewModel,
                securityManager = securityManager,
                onNavigateToStandaloneSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToAddPassword = { passwordId ->
                    navController.navigate(Screen.AddEditPassword.createRoute(passwordId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAddWifi = { passwordId ->
                    navController.navigate(Screen.AddEditWifi.createRoute(passwordId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAddSshKey = { passwordId ->
                    navController.navigate(Screen.AddEditSshKey.createRoute(passwordId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAddTotp = { totpId ->
                    navController.navigate(Screen.AddEditTotp.createRoute(totpId))
                },
                onNavigateToQuickTotpScan = {
                    navController.navigate(Screen.QuickTotpScan.route)
                },
                pendingSteamQrResult = steamQrResult,
                pendingSteamQrAccountId = steamQrAccountId,
                onConsumePendingSteamQrResult = {
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.remove<String>("steam_qr_result")
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.remove<Long>("steam_qr_account_id")
                },
                onScanSteamQrCode = { accountId ->
                    navController.navigate(Screen.SteamQrScan.createRoute(accountId)) {
                        launchSingleTop = true
                    }
                },
                pendingPasswordAuthenticatorQrResult = mainQrResult,
                onConsumePendingPasswordAuthenticatorQrResult = {
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.remove<String>("qr_result")
                },
                onScanPasswordAuthenticatorQrCode = {
                    navController.navigate(Screen.QrScanner.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToFidoQrScan = {
                    navController.navigate(Screen.FidoQrScan.route)
                },
                onNavigateToAddBankCard = { cardId ->
                    navController.navigate(Screen.AddEditBankCard.createRoute(cardId))
                },
                onNavigateToAddDocument = { documentId ->
                    navController.navigate(Screen.AddEditDocument.createRoute(documentId))
                },
                onNavigateToAddBillingAddress = { addressId ->
                    navController.navigate(Screen.AddEditBillingAddress.createRoute(addressId))
                },
                onNavigateToWalletAdd = { initialType ->
                    navController.navigate(Screen.WalletAdd.createRoute(initialType.name))
                },
                onPreparePasswordAddStorageDefaults = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.setPendingAddStorageDefaults(
                            PendingAddStorageDefaults(
                                categoryId = categoryId,
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = keepassGroupPath,
                                mdbxDatabaseId = mdbxDatabaseId,
                                mdbxFolderId = mdbxFolderId,
                                bitwardenVaultId = bitwardenVaultId,
                                bitwardenFolderId = bitwardenFolderId
                            )
                        )
                },
                onPrepareTotpAddStorageDefaults = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.setPendingAddStorageDefaults(
                            PendingAddStorageDefaults(
                                categoryId = categoryId,
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = keepassGroupPath,
                                mdbxDatabaseId = mdbxDatabaseId,
                                mdbxFolderId = mdbxFolderId,
                                bitwardenVaultId = bitwardenVaultId,
                                bitwardenFolderId = bitwardenFolderId
                            )
                        )
                },
                onPrepareNoteAddStorageDefaults = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.setPendingAddStorageDefaults(
                            PendingAddStorageDefaults(
                                categoryId = categoryId,
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = keepassGroupPath,
                                mdbxDatabaseId = mdbxDatabaseId,
                                mdbxFolderId = mdbxFolderId,
                                bitwardenVaultId = bitwardenVaultId,
                                bitwardenFolderId = bitwardenFolderId
                            )
                        )
                },
                onPrepareWalletAddStorageDefaults = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.setPendingAddStorageDefaults(
                            PendingAddStorageDefaults(
                                categoryId = categoryId,
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = keepassGroupPath,
                                mdbxDatabaseId = mdbxDatabaseId,
                                mdbxFolderId = mdbxFolderId,
                                bitwardenVaultId = bitwardenVaultId,
                                bitwardenFolderId = bitwardenFolderId
                            )
                        )
                },
                onNavigateToAddNote = { noteId ->
                    if (noteId == null) {
                        navController.navigate(Screen.AddEditNote.createRoute()) {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(Screen.NoteDetail.createRoute(noteId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onNavigateToSearchedNote = { noteId, highlightQuery ->
                    navController.navigate(Screen.NoteDetail.createRoute(noteId, highlightQuery)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPasswordDetail = { passwordId ->
                    navController.navigate(Screen.PasswordDetail.createRoute(passwordId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMdbxCommitHistory = { databaseId ->
                    navController.navigate(
                        Screen.MdbxManager.createRoute(
                            databaseId = databaseId,
                            page = Screen.MdbxManager.PAGE_COMMIT_HISTORY
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPasskeyDetail = { recordId ->
                    navController.navigate(Screen.PasskeyDetail.createRoute(recordId))
                },
                onNavigateToBankCardDetail = { cardId ->
                    navController.navigate("bank_card_detail/$cardId")
                },
                onNavigateToDocumentDetail = { documentId ->
                    navController.navigate(Screen.DocumentDetail.createRoute(documentId))
                },
                onNavigateToBillingAddressDetail = { addressId ->
                    navController.navigate(Screen.BillingAddressDetail.createRoute(addressId))
                },
                onNavigateToChangePassword = {
                    navController.navigate(Screen.ChangePassword.route)
                },
                onNavigateToSecurityQuestion = {
                    navController.navigate(Screen.SecurityQuestion.route)
                },
                onNavigateToMasterPasswordLocking = {
                    navController.navigate(Screen.MasterPasswordLockingSettings.route)
                },
                onNavigateToSyncBackup = {
                    navController.navigate(Screen.SyncBackup.route)
                },
                onNavigateToAutofill = {
                    navController.navigate(Screen.AutofillSettings.route)
                },
                onNavigateToPasskeySettings = {
                    navController.navigate(Screen.PasskeySettings.route)
                },
                onNavigateToBottomNavSettings = {
                    navController.navigate(Screen.BottomNavSettings.route)
                },
                onNavigateToColorScheme = {
                    navController.navigate(Screen.ColorSchemeSelection.route)
                },
                onSecurityAnalysis = {
                    navController.navigate(Screen.SecurityAnalysis.route)
                },
                onNavigateToDeveloperSettings = {
                    navController.navigate(Screen.DeveloperSettings.route)
                },
                onNavigateToPermissionManagement = {
                    navController.navigate(Screen.PermissionManagement.route)
                },
                onNavigateToMonicaPlus = {
                    android.util.Log.d("MainActivity", "Navigating to Monica Plus"); navController.navigate(Screen.MonicaPlus.route)
                },
                onNavigateToExtensions = {
                    navController.navigate(Screen.Extensions.route)
                },
                onNavigateToCommonAccountTemplates = {
                    navController.navigate(Screen.CommonAccountTemplates.route)
                },
                onNavigateToPageCustomization = {
                    navController.navigate(Screen.PageAdjustmentCustomization.route)
                },
                onNavigateToAddSend = {
                    navController.navigate(Screen.AddEditSend.createRoute()) {
                        launchSingleTop = true
                    }
                },
                onClearAllData = { clearPasswords: Boolean, clearTotp: Boolean, clearNotes: Boolean, clearDocuments: Boolean, clearBankCards: Boolean, clearGeneratorHistory: Boolean ->
                    // 清空所有数据
                    android.util.Log.d(
                        "MainActivity",
                        "onClearAllData called with options: passwords=$clearPasswords, totp=$clearTotp, notes=$clearNotes, documents=$clearDocuments, bankCards=$clearBankCards, generatorHistory=$clearGeneratorHistory"
                    )
                    scope.launch {
                        try {
                            // 根据选项清空PasswordEntry表
                            if (clearPasswords) {
                                val passwords = repository.getAllPasswordEntries().first()
                                android.util.Log.d("MainActivity", "Found ${passwords.size} passwords to delete")
                                passwords.forEach { repository.deletePasswordEntry(it) }
                            }
                            
                            // 根据选项清空SecureItem表
                            if (clearTotp || clearDocuments || clearBankCards || clearNotes) {
                                val items = secureItemRepository.getAllItems().first()
                                android.util.Log.d("MainActivity", "Found ${items.size} secure items to delete")
                                items.forEach { item ->
                                    val shouldDelete = when (item.itemType) {
                                        ItemType.TOTP -> clearTotp
                                        ItemType.DOCUMENT -> clearDocuments
                                        ItemType.BANK_CARD -> clearBankCards
                                        ItemType.NOTE -> clearNotes
                                        else -> false
                                    }
                                    if (shouldDelete) {
                                        secureItemRepository.deleteItem(item)
                                    }
                                }
                            }

                            if (clearGeneratorHistory) {
                                passwordHistoryManager.clearHistory()
                            }
                            
                            // 显示成功消息
                            android.widget.Toast.makeText(
                                navController.context,
                                "数据已清空",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            android.util.Log.d("MainActivity", "All selected data cleared successfully")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to clear data", e)
                            android.widget.Toast.makeText(
                                navController.context,
                                "清空失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                initialTab = tab
            )
            } // end CompositionLocalProvider
        }

        composable(
            route = Screen.AddEditPassword.route,
            arguments = listOf(
                navArgument("passwordId") {
                    type = NavType.StringType
                },
                navArgument("initialType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val passwordId = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            val initialType = backStackEntry.arguments?.getString("initialType")
            val pendingStorageDefaults = remember(backStackEntry, passwordId) {
                if (passwordId > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }
            val qrResult by backStackEntry.savedStateHandle
                .getStateFlow<String?>("qr_result", null)
                .collectAsState()
            var replacementPasswordDetailId by remember(backStackEntry, passwordId) {
                mutableStateOf<Long?>(null)
            }
            val navigateBackFromAddEditPassword = {
                val replacementId = replacementPasswordDetailId
                replacementPasswordDetailId = null
                if (passwordId > 0 && replacementId != null && replacementId != passwordId) {
                    navController.navigate(Screen.PasswordDetail.createRoute(replacementId)) {
                        popUpTo(Screen.PasswordDetail.route) { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Main.createRoute()) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            key(passwordId) {
                AddEditPasswordScreen(
                    viewModel = viewModel,
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    noteViewModel = noteViewModel,
                    localKeePassViewModel = localKeePassViewModel,
                    localMdbxViewModel = mdbxViewModel,
                    passwordId = if (passwordId == -1L) null else passwordId,
                    initialCategoryId = pendingStorageDefaults?.categoryId,
                    initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                    initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                    initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                    initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId,
                    initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                    initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId,
                    pendingQrResult = qrResult,
                    initialLoginType = initialType,
                    onConsumePendingQrResult = {
                        backStackEntry.savedStateHandle.remove<String>("qr_result")
                    },
                    onScanAuthenticatorQrCode = {
                        navController.navigate(Screen.QrScanner.route) {
                            launchSingleTop = true
                        }
                    },
                    onSwitchToWifi = { targetId ->
                        val route = Screen.AddEditWifi.createRoute(targetId)
                        navController.navigate(route) {
                            popUpTo(Screen.AddEditPassword.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSwitchToSshKey = { targetId ->
                        val route = Screen.AddEditSshKey.createRoute(targetId)
                        navController.navigate(route) {
                            popUpTo(Screen.AddEditPassword.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSaveCompleted = { savedPasswordId ->
                        replacementPasswordDetailId = savedPasswordId
                    },
                    onNavigateBack = navigateBackFromAddEditPassword
                )
            }
            }
        }

        composable(
            route = Screen.AddEditWifi.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val passwordIdArg = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            val navigateBack = {
                val popped = navController.popBackStack()
                if (!popped) {
                    navController.navigate(Screen.Main.createRoute()) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            val qrResult = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.get<String>("qr_result")
            takagi.ru.monica.ui.screens.AddEditWifiScreen(
                viewModel = viewModel,
                localKeePassViewModel = localKeePassViewModel,
                passwordId = if (passwordIdArg == -1L) null else passwordIdArg,
                pendingQrResult = qrResult,
                onConsumePendingQrResult = {
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.remove<String>("qr_result")
                },
                onScanQrCode = {
                    navController.navigate(Screen.QrScanner.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateBack = navigateBack,
                onNavigateToPassword = {
                    navController.navigate(Screen.AddEditPassword.createRoute(null)) {
                        popUpTo(Screen.AddEditWifi.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToBarcode = {
                    navController.navigate(Screen.AddEditPassword.createRoute(null, LOGIN_TYPE_BARCODE)) {
                        popUpTo(Screen.AddEditWifi.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToSshKey = {
                    navController.navigate(Screen.AddEditSshKey.createRoute(null)) {
                        popUpTo(Screen.AddEditWifi.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
            }
        }

        composable(
            route = Screen.WifiDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val wifiId = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            if (wifiId > 0) {
                val navigateBack = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Main.createRoute()) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                takagi.ru.monica.ui.screens.WifiDetailScreen(
                    viewModel = viewModel,
                    passwordId = wifiId,
                    onNavigateBack = navigateBack,
                    onEdit = { id ->
                        navController.navigate(Screen.AddEditWifi.createRoute(id)) {
                            launchSingleTop = true
                        }
                    },
                    onCreateSend = { title, text ->
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.setPendingSendDraft(
                                PendingSendDraft(
                                    title = title,
                                    text = text
                                )
                            )
                        navController.navigate(Screen.AddEditSend.createRoute()) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.AddEditSshKey.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val passwordIdArg = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            val navigateBack = {
                val popped = navController.popBackStack()
                if (!popped) {
                    navController.navigate(Screen.Main.createRoute()) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            val pendingStorageDefaults = remember(backStackEntry, passwordIdArg) {
                if (passwordIdArg > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }
            takagi.ru.monica.ui.screens.AddEditSshKeyScreen(
                viewModel = viewModel,
                localKeePassViewModel = localKeePassViewModel,
                passwordId = if (passwordIdArg == -1L) null else passwordIdArg,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId,
                onNavigateBack = navigateBack,
                onNavigateToPassword = {
                    navController.navigate(Screen.AddEditPassword.createRoute(null)) {
                        popUpTo(Screen.AddEditSshKey.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToBarcode = {
                    navController.navigate(Screen.AddEditPassword.createRoute(null, LOGIN_TYPE_BARCODE)) {
                        popUpTo(Screen.AddEditSshKey.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToWifi = {
                    navController.navigate(Screen.AddEditWifi.createRoute(null)) {
                        popUpTo(Screen.AddEditSshKey.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
            }
        }

        composable(
            route = Screen.SshKeyDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val sshId = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            if (sshId > 0) {
                val navigateBack = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Main.createRoute()) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                takagi.ru.monica.ui.screens.SshKeyDetailScreen(
                    viewModel = viewModel,
                    passwordId = sshId,
                    onNavigateBack = navigateBack,
                    onEdit = { id ->
                        navController.navigate(Screen.AddEditSshKey.createRoute(id)) {
                            launchSingleTop = true
                        }
                    },
                    onCreateSend = { title, text ->
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.setPendingSendDraft(
                                PendingSendDraft(
                                    title = title,
                                    text = text
                                )
                            )
                        navController.navigate(Screen.AddEditSend.createRoute()) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.BarcodeDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val barcodeId = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            if (barcodeId > 0) {
                val navigateBack = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Main.createRoute()) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                takagi.ru.monica.ui.screens.BarcodeDetailScreen(
                    viewModel = viewModel,
                    passwordId = barcodeId,
                    onNavigateBack = navigateBack,
                    onEdit = { id ->
                        navController.navigate(Screen.AddEditPassword.createRoute(id)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.AddEditTotp.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val totpId = backStackEntry.arguments?.getString("totpId")?.toLongOrNull() ?: 0L
            val currentTotpFilter by totpViewModel.categoryFilter.collectAsState()
            val displayTotpItems by totpViewModel.allTotpItems.collectAsState()
            val pendingStorageDefaults = remember(backStackEntry, totpId) {
                if (totpId != 0L) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }

            var initialData by remember { mutableStateOf<takagi.ru.monica.data.model.TotpData?>(null) }
            var initialTitle by remember { mutableStateOf("") }
            var initialNotes by remember { mutableStateOf("") }
            var initialKeePassGroupPath by remember { mutableStateOf<String?>(null) }
            var initialMdbxDatabaseIdFromItem by remember { mutableStateOf<Long?>(null) }
            var initialBitwardenVaultId by remember { mutableStateOf<Long?>(null) }
            var initialBitwardenFolderId by remember { mutableStateOf<String?>(null) }
            var initialReplicaGroupId by remember { mutableStateOf<String?>(null) }
            var initialIsFavorite by remember { mutableStateOf(false) }
            var isLoading by remember { mutableStateOf(true) }

            // 从QR扫描获取的数据
            val qrResult = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.get<String>("qr_result")
            val externalTotpImport = pendingExternalTotpImport?.takeIf { totpId == 0L }
            val externalQrResult = externalTotpImport?.uri?.takeIf { qrResult == null }
            val pendingTotpImportResult = qrResult ?: externalQrResult

            LaunchedEffect(totpId, displayTotpItems) {
                val item = when {
                    totpId > 0 -> totpViewModel.getTotpItemById(totpId)
                    totpId < 0 -> displayTotpItems.firstOrNull { it.id == totpId }
                    else -> null
                }
                if (item != null) {
                    initialTitle = item.title
                    initialNotes = item.notes
                    initialKeePassGroupPath = item.keepassGroupPath
                    initialMdbxDatabaseIdFromItem = item.mdbxDatabaseId
                    initialBitwardenVaultId = item.bitwardenVaultId
                    initialBitwardenFolderId = item.bitwardenFolderId
                    initialReplicaGroupId = item.replicaGroupId
                    initialIsFavorite = item.isFavorite
                    initialData = totpViewModel.parseTotpDataForDisplay(item)
                }
                isLoading = false
            }

            if (!isLoading) {
                val totpCategories by totpViewModel.categories.collectAsState()
                data class TotpStorageDefaults(
                    val categoryId: Long? = null,
                    val keepassDatabaseId: Long? = null,
                    val keepassGroupPath: String? = null,
                    val mdbxDatabaseId: Long? = null,
                    val bitwardenVaultId: Long? = null,
                    val bitwardenFolderId: String? = null
                )
                val filterDefaults = remember(currentTotpFilter) {
                    when (val filter = currentTotpFilter) {
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Custom -> {
                            TotpStorageDefaults(categoryId = filter.categoryId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase -> {
                            TotpStorageDefaults(keepassDatabaseId = filter.databaseId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> {
                            TotpStorageDefaults(keepassDatabaseId = filter.databaseId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> {
                            TotpStorageDefaults(keepassDatabaseId = filter.databaseId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> {
                            TotpStorageDefaults(
                                keepassDatabaseId = filter.databaseId,
                                keepassGroupPath = filter.groupPath
                            )
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase -> {
                            TotpStorageDefaults(mdbxDatabaseId = filter.databaseId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault -> {
                            TotpStorageDefaults(bitwardenVaultId = filter.vaultId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> {
                            TotpStorageDefaults(bitwardenVaultId = filter.vaultId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> {
                            TotpStorageDefaults(bitwardenVaultId = filter.vaultId)
                        }
                        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> {
                            TotpStorageDefaults(
                                bitwardenVaultId = filter.vaultId,
                                bitwardenFolderId = filter.folderId
                            )
                        }
                        else -> TotpStorageDefaults()
                    }
                }
                val hasPendingStorageDefaults = pendingStorageDefaults?.hasAnyValue() == true
                val initialCategoryId = when {
                    initialData?.categoryId != null -> initialData?.categoryId
                    hasPendingStorageDefaults -> pendingStorageDefaults?.categoryId
                    else -> filterDefaults.categoryId
                }
                val initialKeePassDatabaseId = when {
                    initialData?.keepassDatabaseId != null -> initialData?.keepassDatabaseId
                    hasPendingStorageDefaults -> pendingStorageDefaults?.keepassDatabaseId
                    else -> filterDefaults.keepassDatabaseId
                }
                val resolvedInitialKeePassGroupPath = when {
                    !initialKeePassGroupPath.isNullOrBlank() -> initialKeePassGroupPath
                    hasPendingStorageDefaults -> pendingStorageDefaults?.keepassGroupPath
                    else -> filterDefaults.keepassGroupPath
                }
                val initialMdbxDatabaseId = when {
                    initialMdbxDatabaseIdFromItem != null -> initialMdbxDatabaseIdFromItem
                    hasPendingStorageDefaults -> pendingStorageDefaults?.mdbxDatabaseId
                    else -> filterDefaults.mdbxDatabaseId
                }
                val initialVaultId = when {
                    initialBitwardenVaultId != null -> initialBitwardenVaultId
                    hasPendingStorageDefaults -> pendingStorageDefaults?.bitwardenVaultId
                    else -> filterDefaults.bitwardenVaultId
                }
                val initialFolderId = when {
                    !initialBitwardenFolderId.isNullOrBlank() -> initialBitwardenFolderId
                    hasPendingStorageDefaults -> pendingStorageDefaults?.bitwardenFolderId
                    else -> filterDefaults.bitwardenFolderId
                }
                key(totpId) {
                takagi.ru.monica.ui.screens.AddEditTotpScreen(
                    totpId = if (totpId > 0) totpId else null,
                    initialData = initialData,
                    initialTitle = initialTitle,
                    initialNotes = initialNotes,
                    initialCategoryId = initialCategoryId,
                    initialKeePassDatabaseId = initialKeePassDatabaseId,
                    initialKeePassGroupPath = resolvedInitialKeePassGroupPath,
                    initialMdbxDatabaseId = initialMdbxDatabaseId,
                    initialBitwardenVaultId = initialVaultId,
                    initialBitwardenFolderId = initialFolderId,
                    initialReplicaGroupId = initialReplicaGroupId,
                    initialIsFavorite = initialIsFavorite,
                    categories = totpCategories,
                    passwordViewModel = viewModel,
                    totpViewModel = totpViewModel,
                    localKeePassViewModel = localKeePassViewModel,
                    pendingQrResult = pendingTotpImportResult,
                    onConsumePendingQrResult = {
                        if (externalQrResult != null) {
                            onConsumeExternalTotpImport(externalTotpImport.id)
                        } else {
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.remove<String>("qr_result")
                        }
                    },
                    onSave = { title, notes, totpData, isFavorite, targets, onComplete ->
                        totpViewModel.saveTotpAcrossTargets(
                            id = if (totpId > 0) totpId else null,
                            title = title,
                            notes = notes,
                            totpData = totpData,
                            isFavorite = isFavorite,
                            targets = targets,
                            onComplete = { saved ->
                                if (saved) {
                                    totpViewModel.revealSavedTotpTargets(targets)
                                    navController.popBackStack()
                                }
                                onComplete(saved)
                            }
                        )
                    },
                    onBatchImport = { items, targets, onComplete ->
                        totpViewModel.saveTotpMigrationBatch(
                            items = items,
                            targets = targets,
                            onComplete = { result ->
                                if (result.importedCount > 0) {
                                    totpViewModel.revealSavedTotpTargets(targets)
                                }
                                onComplete(result)
                            }
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onScanQrCode = {
                        navController.navigate(Screen.QrScanner.route)
                    }
                )
                }
            }
            }
        }

        composable(
            route = Screen.WalletAdd.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val initialTypeRaw = backStackEntry.arguments?.getString("initialType").orEmpty()
            val initialType = runCatching {
                takagi.ru.monica.ui.screens.CardWalletTab.valueOf(initialTypeRaw)
            }.getOrDefault(takagi.ru.monica.ui.screens.CardWalletTab.BANK_CARDS)
            val pendingStorageDefaults = remember(backStackEntry) {
                navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
            }
            val walletAddStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            var selectedType by androidx.compose.runtime.saveable.rememberSaveable(initialTypeRaw) {
                mutableStateOf(initialType)
            }
            takagi.ru.monica.ui.UnifiedWalletAddScreen(
                selectedType = selectedType,
                onTypeSelected = { selectedType = it },
                onNavigateBack = {
                    navController.popBackStack()
                },
                bankCardViewModel = bankCardViewModel,
                documentViewModel = documentViewModel,
                billingAddressViewModel = billingAddressViewModel,
                stateHolder = walletAddStateHolder,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId,
                initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId
            )
            }
        }

        composable(
            route = Screen.AddEditBankCard.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val cardId = backStackEntry.arguments?.getString("cardId")?.toLongOrNull() ?: -1L
            val pendingStorageDefaults = remember(backStackEntry, cardId) {
                if (cardId > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }

            takagi.ru.monica.ui.screens.AddEditBankCardScreen(
                viewModel = bankCardViewModel,
                cardId = if (cardId > 0) cardId else null,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId,
                initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.AddEditDocument.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val documentId = backStackEntry.arguments?.getString("documentId")?.toLongOrNull() ?: -1L
            val pendingStorageDefaults = remember(backStackEntry, documentId) {
                if (documentId > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }

            takagi.ru.monica.ui.screens.AddEditDocumentScreen(
                viewModel = documentViewModel,
                documentId = if (documentId > 0) documentId else null,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId,
                initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.AddEditBillingAddress.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val addressId = backStackEntry.arguments?.getString("addressId")?.toLongOrNull() ?: -1L
            val pendingStorageDefaults = remember(backStackEntry, addressId) {
                if (addressId > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }

            AddEditBillingAddressScreen(
                viewModel = billingAddressViewModel,
                addressId = if (addressId > 0) addressId else null,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = "bank_card_detail/{cardId}",
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val cardId = backStackEntry.arguments?.getString("cardId")?.toLongOrNull() ?: -1L

            if (cardId > 0) {
                takagi.ru.monica.ui.screens.BankCardDetailScreen(
                    viewModel = bankCardViewModel,
                    cardId = cardId,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onEditCard = { id ->
                        navController.navigate(Screen.AddEditBankCard.createRoute(id))
                    }
                )
            }
        }

        composable(
            route = Screen.AddEditNote.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull() ?: -1L
            val pendingStorageDefaults = remember(backStackEntry, noteId) {
                if (noteId > 0) {
                    navController.previousBackStackEntry?.savedStateHandle?.clearPendingAddStorageDefaults()
                    null
                } else {
                    navController.previousBackStackEntry?.savedStateHandle?.consumePendingAddStorageDefaults()
                }
            }

            takagi.ru.monica.ui.screens.AddEditNoteScreen(
                noteId = noteId,
                initialCategoryId = pendingStorageDefaults?.categoryId,
                initialKeePassDatabaseId = pendingStorageDefaults?.keepassDatabaseId,
                initialKeePassGroupPath = pendingStorageDefaults?.keepassGroupPath,
                initialMdbxDatabaseId = pendingStorageDefaults?.mdbxDatabaseId,
                initialBitwardenVaultId = pendingStorageDefaults?.bitwardenVaultId,
                initialBitwardenFolderId = pendingStorageDefaults?.bitwardenFolderId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = noteViewModel
            )
            }
        }

        composable(
            route = Screen.AddEditSend.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            AddEditRouteContent {
            val pendingSendDraft = remember(backStackEntry) {
                navController.previousBackStackEntry?.savedStateHandle?.consumePendingSendDraft()
            }

            AddEditSendScreen(
                sendState = bitwardenViewModel.sendState.collectAsState().value,
                sendCreateSuccessVersion = bitwardenViewModel.sendCreateSuccessVersion.collectAsState().value,
                vaults = bitwardenViewModel.vaults.collectAsState().value,
                activeVault = bitwardenViewModel.activeVault.collectAsState().value,
                unlockStateByVault = bitwardenViewModel.unlockStateByVault.collectAsState().value,
                initialTitle = pendingSendDraft?.title.orEmpty(),
                initialText = pendingSendDraft?.text.orEmpty(),
                initialNotes = pendingSendDraft?.notes.orEmpty(),
                onNavigateBack = {
                    navController.popBackStack()
                },
                onCreate = { vaultId, title, text, notes, password, maxAccessCount, hideEmail, hiddenText, expireInDays ->
                    bitwardenViewModel.createTextSend(
                        vaultId = vaultId,
                        title = title,
                        text = text,
                        notes = notes,
                        password = password,
                        maxAccessCount = maxAccessCount,
                        hideEmail = hideEmail,
                        hiddenText = hiddenText,
                        expireInDays = expireInDays
                    )
                },
                onCreateFile = { vaultId, title, fileUri, fileName, notes, password, maxAccessCount, hideEmail, expireInDays ->
                    bitwardenViewModel.createFileSend(
                        vaultId = vaultId,
                        title = title,
                        fileUri = fileUri,
                        fileName = fileName,
                        notes = notes,
                        password = password,
                        maxAccessCount = maxAccessCount,
                        hideEmail = hideEmail,
                        expireInDays = expireInDays
                    )
                }
            )
            }
        }

        composable(
            route = Screen.NoteDetail.route,
            arguments = listOf(
                navArgument("highlight") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull() ?: -1L
            val highlightQuery = backStackEntry.arguments
                ?.getString("highlight")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            if (noteId > 0) {
                takagi.ru.monica.ui.screens.NoteDetailScreen(
                    viewModel = noteViewModel,
                    noteId = noteId,
                    initialHighlightQuery = highlightQuery,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onEditNote = { id ->
                        navController.navigate(Screen.AddEditNote.createRoute(id))
                    },
                    onCreateSend = { title, text ->
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.setPendingSendDraft(
                                PendingSendDraft(
                                    title = title,
                                    text = text
                                )
                            )
                        navController.navigate(Screen.AddEditSend.createRoute()) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.DocumentDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId")?.toLongOrNull() ?: -1L

            if (documentId > 0) {
                takagi.ru.monica.ui.screens.DocumentDetailScreen(
                    viewModel = documentViewModel,
                    documentId = documentId,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onEditDocument = { id ->
                        navController.navigate(Screen.AddEditDocument.createRoute(id))
                    }
                )
            }
        }

        composable(
            route = Screen.BillingAddressDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val addressId = backStackEntry.arguments?.getString("addressId")?.toLongOrNull() ?: -1L

            if (addressId > 0) {
                BillingAddressDetailScreen(
                    viewModel = billingAddressViewModel,
                    addressId = addressId,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onEditAddress = { id ->
                        navController.navigate(Screen.AddEditBillingAddress.createRoute(id))
                    }
                )
            }
        }

        composable(
            route = Screen.PasswordDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val passwordId = backStackEntry.arguments?.getString("passwordId")?.toLongOrNull() ?: -1L
            val scope = rememberCoroutineScope()

            if (passwordId > 0) {
                // WIFI / SSH_KEY 条目走独立详情页；快速探测 loginType 后重定向，避免打开复杂的密码详情屏。
                var redirectChecked by androidx.compose.runtime.saveable.rememberSaveable(passwordId) {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                var redirectedToSpecialized by androidx.compose.runtime.saveable.rememberSaveable(passwordId) {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                val navigateBackFromPasswordDetail = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Main.createRoute()) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(passwordId) {
                    val entry = withContext(Dispatchers.IO) {
                        viewModel.getRawPasswordEntryById(passwordId)
                    }
                    when {
                        entry?.isWifiEntry() == true -> {
                            redirectedToSpecialized = true
                            navController.navigate(Screen.WifiDetail.createRoute(passwordId)) {
                                popUpTo(Screen.PasswordDetail.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        entry?.isSshKeyEntry() == true -> {
                            redirectedToSpecialized = true
                            navController.navigate(Screen.SshKeyDetail.createRoute(passwordId)) {
                                popUpTo(Screen.PasswordDetail.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        entry?.isBarcodeEntry() == true -> {
                            redirectedToSpecialized = true
                            navController.navigate(Screen.BarcodeDetail.createRoute(passwordId)) {
                                popUpTo(Screen.PasswordDetail.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    redirectChecked = true
                }
                AnimatedVisibility(
                    visible = redirectChecked && !redirectedToSpecialized,
                    enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 260),
                        initialOffsetX = { fullWidth -> fullWidth / 8 }
                    ) + fadeIn(animationSpec = tween(durationMillis = 220))
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
                    ) {
                        takagi.ru.monica.ui.screens.PasswordDetailScreen(
                        viewModel = viewModel,
                        passkeyViewModel = passkeyViewModel,
                        noteViewModel = noteViewModel,
                        passwordId = passwordId,
                        biometricEnabled = settings.biometricEnabled,
                        iconCardsEnabled = settings.iconCardsEnabled && settings.passwordPageIconEnabled,
                        unmatchedIconHandlingStrategy = settings.unmatchedIconHandlingStrategy,
                        enableSharedBounds = false,
                        onNavigateBack = navigateBackFromPasswordDetail,
                        onOpenBoundNote = { noteId ->
                            scope.launch {
                                navController.navigate(Screen.NoteDetail.createRoute(noteId)) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onOpenPassword = { targetPasswordId ->
                            scope.launch {
                                navController.navigate(Screen.PasswordDetail.createRoute(targetPasswordId)) {
                                    popUpTo(Screen.PasswordDetail.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                        onCreateSend = { title, text ->
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.setPendingSendDraft(
                                    PendingSendDraft(
                                        title = title,
                                        text = text
                                    )
                                )
                            navController.navigate(Screen.AddEditSend.createRoute()) {
                                launchSingleTop = true
                            }
                        },
                        onEditPassword = { id ->
                            scope.launch {
                                navController.navigate(Screen.AddEditPassword.createRoute(id)) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                    }
                }
            }
        }

        composable(Screen.QrScanner.route) {
            takagi.ru.monica.ui.screens.QrScannerScreen(
                onQrCodeScanned = { qrData ->
                    // 将QR码数据保存到前一个页面的savedStateHandle
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("qr_result", qrData)
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.SteamQrScan.route,
            arguments = listOf(
                navArgument(Screen.SteamQrScan.ARG_ACCOUNT_ID) {
                    type = NavType.LongType
                    defaultValue = 0L
                }
            ),
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val initialSteamAccountId = backStackEntry.arguments
                ?.getLong(Screen.SteamQrScan.ARG_ACCOUNT_ID)
                ?.takeIf { it != 0L }
            SteamQrScannerScreen(
                initialAccountId = initialSteamAccountId,
                onQrCodeScanned = { qrData, accountId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("steam_qr_result", qrData)
                    if (accountId != null) {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("steam_qr_account_id", accountId)
                    }
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.FidoQrScan.route) {
            val context = LocalContext.current
            takagi.ru.monica.ui.screens.QrScannerScreen(
                onQrCodeScanned = { qrData ->
                    val messageRes = launchSystemFidoQrIntent(context, qrData)
                    if (messageRes != null) {
                        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                    }
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // 快速扫码添加验证器：单条直接保存，迁移二维码先预览再批量保存
        composable(Screen.QuickTotpScan.route) {
            val context = LocalContext.current
            var pendingMigrationItems by remember {
                mutableStateOf<List<takagi.ru.monica.util.TotpParseResult>?>(null)
            }
            var isMigrationSaving by remember { mutableStateOf(false) }

            fun quickScanTargetsForCurrentFilter(): List<StorageTarget> {
                return when (val filter = totpViewModel.categoryFilter.value) {
                    TotpCategoryFilter.All,
                    TotpCategoryFilter.Local,
                    TotpCategoryFilter.Starred,
                    TotpCategoryFilter.Uncategorized,
                    TotpCategoryFilter.LocalStarred,
                    TotpCategoryFilter.LocalUncategorized -> listOf(StorageTarget.MonicaLocal(null))
                    is TotpCategoryFilter.Custom -> listOf(StorageTarget.MonicaLocal(filter.categoryId))
                    is TotpCategoryFilter.KeePassDatabase -> listOf(StorageTarget.KeePass(filter.databaseId, null))
                    is TotpCategoryFilter.KeePassGroupFilter -> listOf(StorageTarget.KeePass(filter.databaseId, filter.groupPath))
                    is TotpCategoryFilter.KeePassDatabaseStarred -> listOf(StorageTarget.KeePass(filter.databaseId, null))
                    is TotpCategoryFilter.KeePassDatabaseUncategorized -> listOf(StorageTarget.KeePass(filter.databaseId, null))
                    is TotpCategoryFilter.BitwardenVault -> listOf(StorageTarget.Bitwarden(filter.vaultId, null))
                    is TotpCategoryFilter.BitwardenFolderFilter -> listOf(StorageTarget.Bitwarden(filter.vaultId, filter.folderId))
                    is TotpCategoryFilter.BitwardenVaultStarred -> listOf(StorageTarget.Bitwarden(filter.vaultId, null))
                    is TotpCategoryFilter.BitwardenVaultUncategorized -> listOf(StorageTarget.Bitwarden(filter.vaultId, null))
                    is TotpCategoryFilter.MdbxDatabase -> listOf(StorageTarget.Mdbx(filter.databaseId))
                }
            }

            if (pendingMigrationItems == null) {
                takagi.ru.monica.ui.screens.QrScannerScreen(
                    onQrCodeScanned = onQrCodeScanned@{ qrData ->
                    fun resolveTitle(item: takagi.ru.monica.util.TotpParseResult): String {
                        return item.label.takeIf { it.isNotBlank() }
                            ?: item.totpData.issuer.takeIf { it.isNotBlank() }
                            ?: item.totpData.accountName.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.untitled)
                    }

                    fun saveScannedTotp(
                        title: String,
                        totpData: takagi.ru.monica.data.model.TotpData,
                        onComplete: (Boolean, String?) -> Unit
                    ) {
                        var failureMessage: String? = null
                        totpViewModel.saveTotpAcrossTargets(
                            id = null,
                            title = title,
                            notes = "",
                            totpData = totpData,
                            isFavorite = false,
                            targets = quickScanTargetsForCurrentFilter(),
                            onFailure = { message -> failureMessage = message },
                            onComplete = { saved -> onComplete(saved, failureMessage) }
                        )
                    }

                    when (val scanResult = takagi.ru.monica.util.TotpUriParser.parseScannedContent(qrData)) {
                        is takagi.ru.monica.util.TotpScanParseResult.Single -> {
                            val title = resolveTitle(scanResult.item)
                            val existingItem = totpViewModel.findTotpBySecret(scanResult.item.totpData.secret)
                            if (existingItem != null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.qr_authenticator_duplicate, existingItem.title),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                saveScannedTotp(title, scanResult.item.totpData) { saved, failureMessage ->
                                    Toast.makeText(
                                        context,
                                        if (saved) {
                                            context.getString(R.string.qr_authenticator_added, title)
                                        } else {
                                            failureMessage
                                                ?: context.getString(R.string.save_failed_with_error, title)
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        is takagi.ru.monica.util.TotpScanParseResult.Multiple -> {
                            pendingMigrationItems = scanResult.items
                            return@onQrCodeScanned
                        }
                        is takagi.ru.monica.util.TotpScanParseResult.MigrationFailure -> {
                            Toast.makeText(
                                context,
                                context.getString(
                                    takagi.ru.monica.ui.components.migrationFailureMessageRes(scanResult.reason)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        takagi.ru.monica.util.TotpScanParseResult.UnsupportedPhoneFactor -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.qr_phonefactor_not_supported),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        takagi.ru.monica.util.TotpScanParseResult.InvalidFormat -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.qr_invalid_authenticator),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    navController.popBackStack()
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {}
            }

            pendingMigrationItems?.let { migrationItems ->
                val targets = quickScanTargetsForCurrentFilter()
                takagi.ru.monica.ui.components.TotpMigrationReviewDialog(
                    items = migrationItems,
                    existingTitleFor = { data ->
                        totpViewModel.findTotpByData(data, targets)?.title
                    },
                    isSaving = isMigrationSaving,
                    onDismiss = {
                        pendingMigrationItems = null
                        isMigrationSaving = false
                        navController.popBackStack()
                    },
                    onImport = { selectedItems ->
                        isMigrationSaving = true
                        totpViewModel.saveTotpMigrationBatch(
                            items = selectedItems,
                            targets = targets,
                            onComplete = { result ->
                                isMigrationSaving = false
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.qr_authenticator_migration_result,
                                        result.importedCount,
                                        result.duplicateCount,
                                        result.failedCount
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                                if (result.importedCount > 0) {
                                    totpViewModel.revealSavedTotpTargets(targets)
                                    pendingMigrationItems = null
                                    navController.popBackStack()
                                }
                            }
                        )
                    }
                )
            }
        }

        // 导出数据
        composable(
            route = Screen.ExportData.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val dataExportImportViewModel: takagi.ru.monica.viewmodel.DataExportImportViewModel = viewModel {
                takagi.ru.monica.viewmodel.DataExportImportViewModel(
                    secureItemRepository,
                    repository,
                    navController.context
                )
            }
            val settings by settingsViewModel.settings.collectAsState()
            takagi.ru.monica.ui.screens.ExportDataScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onExportZip = { uri, preferences, backupEncryptionPassword ->
                    dataExportImportViewModel.exportZipBackup(
                        outputUri = uri,
                        preferences = preferences,
                        backupEncryptionPassword = backupEncryptionPassword,
                    )
                },
                onPrepareZip = { preferences, backupEncryptionPassword ->
                    dataExportImportViewModel.prepareZipBackup(
                        preferences = preferences,
                        backupEncryptionPassword = backupEncryptionPassword,
                    )
                },
                onWritePreparedZip = { uri, zipFile, message ->
                    dataExportImportViewModel.writePreparedZipBackup(uri, zipFile, message)
                },
                onExportKdbx = { uri, password ->
                    val ctx = navController.context
                    val outputStream = ctx.contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val result = keePassViewModel.exportToLocalKdbx(ctx, outputStream, password)
                        result.fold(
                            onSuccess = { count: Int ->
                                Result.success("成功导出 $count 条记录到 KDBX 文件")
                            },
                            onFailure = { error: Throwable ->
                                Result.failure(error)
                            }
                        )
                    } else {
                        Result.failure(Exception("无法打开文件"))
                    }
                },
                biometricEnabled = settings.biometricEnabled,
                onLoadSteamMaFileCandidates = {
                    dataExportImportViewModel.loadSteamMaFileExportCandidates()
                },
                onPrepareSteamMaFileExport = { accountIds ->
                    dataExportImportViewModel.prepareSteamMaFileExport(accountIds)
                },
                onWritePreparedSteamMaFileExport = { uri, preparedExport ->
                    dataExportImportViewModel.writePreparedSteamMaFileExport(uri, preparedExport)
                }
            )
        }

        // 导入数据
        composable(
            route = Screen.ImportData.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val dataExportImportViewModel: takagi.ru.monica.viewmodel.DataExportImportViewModel = viewModel {
                takagi.ru.monica.viewmodel.DataExportImportViewModel(
                    secureItemRepository,
                    repository,
                    navController.context
                )
            }
            takagi.ru.monica.ui.screens.ImportDataScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onImport = { uri ->
                    dataExportImportViewModel.importData(uri)
                },
                onImportKeePassCsv = { uri ->
                    dataExportImportViewModel.importKeePassCsv(uri)
                },
                onImportBitwardenCsv = { uri ->
                    dataExportImportViewModel.importBitwardenCsv(uri)
                },
                onImportProtonPassCsv = { uri ->
                    dataExportImportViewModel.importProtonPassCsv(uri)
                },
                onImportPasswordKeyboardCsv = { uri, tagHandling ->
                    dataExportImportViewModel.importPasswordKeyboardCsv(uri, tagHandling)
                },
                onImportAegis = { uri ->
                    dataExportImportViewModel.importAegisJson(uri)
                },
                onImportEncryptedAegis = { uri, password ->
                    dataExportImportViewModel.importEncryptedAegisJson(uri, password)
                },
                onImportSteamMaFile = { uri ->
                    dataExportImportViewModel.importSteamMaFile(uri)
                },
                onBeginSteamLoginImport = { userName, password, customName ->
                    dataExportImportViewModel.beginSteamLoginImport(userName, password, customName)
                },
                onSubmitSteamLoginImportCode = { pendingSessionId, code, confirmationType, customName ->
                    dataExportImportViewModel.submitSteamLoginImportCode(
                        pendingSessionId = pendingSessionId,
                        code = code,
                        confirmationType = confirmationType,
                        customName = customName
                    )
                },
                onClearSteamLoginImportSession = { sessionId ->
                    dataExportImportViewModel.clearSteamLoginImportSession(sessionId)
                },
                onImportZip = { uri, password ->
                    dataExportImportViewModel.importZipBackup(uri, password)
                },
                onImportStratum = { uri, password ->
                    dataExportImportViewModel.importStratum(uri, password)
                },
                onImportKdbx = { uri, password, keyFileUri ->
                    val ctx = navController.context
                    val result = keePassViewModel.importFromLocalKdbx(
                        context = ctx,
                        sourceUri = uri,
                        kdbxPassword = password,
                        keyFileUri = keyFileUri
                    )
                    result.fold(
                        onSuccess = { count: Int -> Result.success(count) },
                        onFailure = { error: Throwable -> Result.failure(error) }
                    )
                }
            )
        }

        composable(
            route = Screen.ChangePassword.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                takagi.ru.monica.ui.screens.ChangePasswordScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onPasswordChanged = { currentPassword, newPassword ->
                        // TODO: 实现修改密码逻辑
                        viewModel.changePassword(currentPassword, newPassword)
                    }
                )
            }
        }

        composable(
            route = Screen.SecurityQuestion.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                SecurityQuestionsSetupScreen(
                    securityManager = securityManager,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onSetupComplete = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val scope = rememberCoroutineScope()

            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onResetPassword = {
                    navController.navigate(Screen.ResetPassword.createRoute()) {
                        launchSingleTop = true
                    }
                },
                onSecurityQuestions = {
                    navController.navigate(Screen.SecurityQuestionsSetup.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMasterPasswordLocking = {
                    navController.navigate(Screen.MasterPasswordLockingSettings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSyncBackup = {
                    navController.navigate(Screen.SyncBackup.route)
                },
                onNavigateToAutofill = {
                    navController.navigate(Screen.AutofillSettings.route)
                },
                onNavigateToPasskeySettings = {
                    navController.navigate(Screen.PasskeySettings.route)
                },
                onNavigateToBottomNavSettings = {
                    navController.navigate(Screen.BottomNavSettings.route)
                },
                onNavigateToColorScheme = {
                    navController.navigate(Screen.ColorSchemeSelection.route)
                },
                onSecurityAnalysis = {
                    navController.navigate(Screen.SecurityAnalysis.route)
                },
                onNavigateToDeveloperSettings = {
                    navController.navigate(Screen.DeveloperSettings.route)
                },
                onNavigateToPermissionManagement = {
                    navController.navigate(Screen.PermissionManagement.route)
                },
                onNavigateToMonicaPlus = {
                    android.util.Log.d("MainActivity", "Navigating to Monica Plus"); navController.navigate(Screen.MonicaPlus.route)
                },
                onNavigateToExtensions = {
                    navController.navigate(Screen.Extensions.route)
                },
                onNavigateToPageCustomization = {
                    navController.navigate(Screen.PageAdjustmentCustomization.route)
                },
                onNavigateToMdbx = {
                    navController.navigate(Screen.MdbxManager.createRoute()) {
                        popUpTo(Screen.MdbxManager.routePattern) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onClearAllData = { clearPasswords: Boolean, clearTotp: Boolean, clearNotes: Boolean, clearDocuments: Boolean, clearBankCards: Boolean, clearGeneratorHistory: Boolean ->
                    // 清空所有数据
                    android.util.Log.d(
                        "MainActivity",
                        "onClearAllData called with options: passwords=$clearPasswords, totp=$clearTotp, notes=$clearNotes, documents=$clearDocuments, bankCards=$clearBankCards, generatorHistory=$clearGeneratorHistory"
                    )
                    scope.launch {
                        try {
                            // 根据选项清空PasswordEntry表
                            if (clearPasswords) {
                                val passwords = repository.getAllPasswordEntries().first()
                                android.util.Log.d("MainActivity", "Found ${passwords.size} passwords to delete")
                                passwords.forEach { repository.deletePasswordEntry(it) }
                            }
                            
                            // 根据选项清空SecureItem表
                            if (clearTotp || clearDocuments || clearBankCards || clearNotes) {
                                val items = secureItemRepository.getAllItems().first()
                                android.util.Log.d("MainActivity", "Found ${items.size} secure items to delete")
                                items.forEach { item ->
                                    val shouldDelete = when (item.itemType) {
                                        ItemType.TOTP -> clearTotp
                                        ItemType.DOCUMENT -> clearDocuments
                                        ItemType.BANK_CARD -> clearBankCards
                                        ItemType.NOTE -> clearNotes
                                        else -> false
                                    }
                                    if (shouldDelete) {
                                        secureItemRepository.deleteItem(item)
                                    }
                                }
                            }

                            if (clearGeneratorHistory) {
                                passwordHistoryManager.clearHistory()
                            }
                            
                            // 显示成功消息
                            android.widget.Toast.makeText(
                                navController.context,
                                "数据已清空",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            android.util.Log.d("MainActivity", "All selected data cleared successfully")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to clear data", e)
                            android.widget.Toast.makeText(
                                navController.context,
                                "清空失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
            }
        }

        composable(
            route = Screen.MasterPasswordLockingSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                MasterPasswordLockingSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onResetPassword = {
                        navController.navigate(Screen.ResetPassword.createRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onSecurityQuestions = {
                        navController.navigate(Screen.SecurityQuestionsSetup.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.BottomNavSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            BottomNavSettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.ResetPassword.route,
            arguments = listOf(navArgument("skipCurrentPassword") {
                type = NavType.BoolType
                defaultValue = false
            }),
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val skipCurrentPassword = backStackEntry.arguments?.getBoolean("skipCurrentPassword") ?: false
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            ResetPasswordScreen(
                securityManager = securityManager,
                skipCurrentPassword = skipCurrentPassword,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onResetSuccess = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
            }
        }

        composable(Screen.ForgotPassword.route) {
            // Check if security questions are set and route accordingly
            LaunchedEffect(Unit) {
                if (securityManager.areSecurityQuestionsSet()) {
                    navController.navigate(Screen.SecurityQuestionsVerification.route) {
                        popUpTo(Screen.ForgotPassword.route) { inclusive = true }
                    }
                }
            }

            // Show full reset option if no security questions are set
            if (!securityManager.areSecurityQuestionsSet()) {
                ForgotPasswordScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onResetComplete = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.SecurityQuestionsSetup.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            SecurityQuestionsSetupScreen(
                securityManager = securityManager,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSetupComplete = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.SecurityQuestionsVerification.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            SecurityQuestionsVerificationScreen(
                securityManager = securityManager,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onVerificationSuccess = {
                    navController.navigate(Screen.ResetPassword.createRoute(skipCurrentPassword = true)) {
                        popUpTo(Screen.SecurityQuestionsVerification.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.SupportAuthor.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            SupportAuthorScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRequestPermission = onPermissionRequested
            )
        }

        composable(
            route = Screen.WebDavBackup.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            WebDavBackupScreen(
                passwordRepository = repository,
                secureItemRepository = secureItemRepository,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.OneDriveBackup.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            OneDriveBackupScreen(
                passwordRepository = repository,
                secureItemRepository = secureItemRepository,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.MdbxManager.routePattern,
            arguments = listOf(
                navArgument(Screen.MdbxManager.ARG_DATABASE_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(Screen.MdbxManager.ARG_PAGE) {
                    type = NavType.StringType
                    defaultValue = Screen.MdbxManager.PAGE_HOME
                }
            ),
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val initialDatabaseId = backStackEntry.arguments
                ?.getLong(Screen.MdbxManager.ARG_DATABASE_ID)
                ?.takeIf { it > 0L }
            val initialPage = when (
                backStackEntry.arguments?.getString(Screen.MdbxManager.ARG_PAGE)
            ) {
                Screen.MdbxManager.PAGE_COMMIT_HISTORY -> MdbxManagerInitialPage.COMMIT_HISTORY
                else -> if (initialDatabaseId != null) {
                    MdbxManagerInitialPage.DETAIL
                } else {
                    MdbxManagerInitialPage.HOME
                }
            }
            MdbxManagerScreen(
                viewModel = mdbxViewModel,
                initialDatabaseId = initialDatabaseId,
                initialPage = initialPage,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToLocalCreate = {
                    navController.navigate(Screen.MdbxLocalCreate.route)
                },
                onNavigateToLocalOpen = {
                    navController.navigate(Screen.MdbxLocalOpen.route)
                },
                onNavigateToWebDavCreate = {
                    navController.navigate(Screen.MdbxWebDavCreate.route)
                },
                onNavigateToWebDavOpen = {
                    navController.navigate(Screen.MdbxWebDavOpen.route)
                },
                onNavigateToOneDriveCreate = {
                    navController.navigate(Screen.MdbxOneDriveCreate.route)
                },
                onNavigateToOneDriveOpen = {
                    navController.navigate(Screen.MdbxOneDriveOpen.route)
                }
            )
        }

        composable(
            route = Screen.MdbxLocalCreate.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxLocalCreateScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MdbxLocalOpen.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxLocalOpenScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MdbxWebDavCreate.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxWebDavCreateScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MdbxWebDavOpen.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxWebDavOpenScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MdbxOneDriveCreate.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxOneDriveCreateScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MdbxOneDriveOpen.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            MdbxOneDriveOpenScreen(
                viewModel = mdbxViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AutofillSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                AutofillSettingsV2Screen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToBlockedFields = {
                        navController.navigate(Screen.AutofillBlockedFields.route)
                    },
                    onNavigateToSaveBlockedTargets = {
                        navController.navigate(Screen.AutofillSaveBlockedTargets.route)
                    }
                )
            }
        }

        composable(
            route = Screen.AutofillBlockedFields.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                AutofillBlockedFieldsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.AutofillSaveBlockedTargets.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                AutofillSaveBlockedTargetsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.PasskeySettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
                takagi.ru.monica.ui.screens.PasskeySettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.PasskeyDetail.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")?.toLongOrNull() ?: -1L
            takagi.ru.monica.ui.screens.PasskeyDetailScreen(
                recordId = recordId,
                passkeyViewModel = passkeyViewModel,
                passwordViewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPasswordDetail = { passwordId ->
                    navController.navigate(Screen.PasswordDetail.createRoute(passwordId))
                }
            )
        }

        composable(
            route = Screen.SecurityAnalysis.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val context = LocalContext.current
            val passkeySupportCatalog = remember(context.applicationContext) {
                takagi.ru.monica.utils.PasskeySupportCatalog(context.applicationContext)
            }
            val securityViewModel: takagi.ru.monica.viewmodel.SecurityAnalysisViewModel = viewModel {
                takagi.ru.monica.viewmodel.SecurityAnalysisViewModel(
                    repository,
                    securityManager,
                    database.localKeePassDatabaseDao(),
                    database.bitwardenVaultDao(),
                    database.passkeyDao(),
                    passkeySupportCatalog
                )
            }
            val securitySettings by settingsViewModel.settings.collectAsState()
            val analysisData by securityViewModel.analysisData.collectAsState()
            LaunchedEffect(securitySettings.securityAnalysisAutoEnabled) {
                securityViewModel.setAutoAnalysisEnabled(securitySettings.securityAnalysisAutoEnabled)
            }

            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.SecurityAnalysisScreen(
                analysisData = analysisData,
                autoAnalysisEnabled = securitySettings.securityAnalysisAutoEnabled,
                onStartAnalysis = {
                    securityViewModel.refreshRealtimeAnalysis()
                },
                onAutoAnalysisEnabledChange = { enabled ->
                    settingsViewModel.updateSecurityAnalysisAutoEnabled(enabled)
                },
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPassword = { passwordId ->
                    navController.navigate(Screen.PasswordDetail.createRoute(passwordId))
                },
                onSelectScope = { scopeKey ->
                    securityViewModel.selectScope(scopeKey)
                }
            )
            }
        }
        
        composable(
            route = Screen.QuickSetup.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val closeQuickSetup = {
                quickSetupDismissedThisSession = true
                if (!navController.popBackStack()) {
                    navController.navigate(Screen.Main.createRoute()) {
                        launchSingleTop = true
                    }
                }
            }
            QuickSetupScreen(
                settingsViewModel = settingsViewModel,
                securityManager = securityManager,
                onSkip = closeQuickSetup,
                onFinish = closeQuickSetup,
                onOpenMasterPassword = {
                    navController.navigate(
                        if (securityManager.isMasterPasswordSet()) {
                            Screen.ChangePassword.route
                        } else {
                            Screen.ResetPassword.createRoute(skipCurrentPassword = true)
                        }
                    )
                },
                onOpenSecurityQuestions = {
                    navController.navigate(Screen.SecurityQuestionsSetup.route)
                },
                onOpenAutofillSettings = {
                    navController.navigate(Screen.AutofillSettings.route)
                },
                onOpenBitwardenSettings = {
                    navController.navigate(Screen.BitwardenSettings.route)
                },
                onOpenWebDavBackup = {
                    navController.navigate(Screen.WebDavBackup.route)
                },
                onOpenLocalKeePass = {
                    navController.navigate(Screen.LocalKeePass.route)
                },
                onOpenImportData = {
                    navController.navigate(Screen.ImportData.route)
                },
                onOpenMonicaPlus = {
                    navController.navigate(Screen.MonicaPlus.route)
                }
            )
        }

        composable(
            route = Screen.DeveloperSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.DeveloperSettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToMdbx = {
                    navController.navigate(Screen.MdbxManager.createRoute()) {
                        popUpTo(Screen.MdbxManager.routePattern) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
            }
        }
        
        composable(
            route = Screen.Extensions.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val settings by settingsViewModel.settings.collectAsState()
            val totpItems by totpViewModel.totpItems.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.ExtensionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToMonicaPlus = {
                    navController.navigate(Screen.MonicaPlus.route)
                },
                onNavigateToQuickSetup = {
                    navController.navigate(Screen.QuickSetup.route)
                },
                isPlusActivated = settings.isPlusActivated,
                hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
                onHapticFeedbackChange = { enabled ->
                    settingsViewModel.updateHapticFeedbackEnabled(enabled)
                },
                validatorVibrationEnabled = settings.validatorVibrationEnabled,
                onValidatorVibrationChange = { enabled ->
                    settingsViewModel.updateValidatorVibrationEnabled(enabled)
                },
                copyNextCodeWhenExpiring = settings.copyNextCodeWhenExpiring,
                onCopyNextCodeWhenExpiringChange = { enabled ->
                    settingsViewModel.updateCopyNextCodeWhenExpiring(enabled)
                },
                smartDeduplicationEnabled = settings.smartDeduplicationEnabled,
                onSmartDeduplicationEnabledChange = { enabled ->
                    settingsViewModel.updateSmartDeduplicationEnabled(enabled)
                },
                clipboardAutoClearSeconds = settings.clipboardAutoClearSeconds,
                onClipboardAutoClearSecondsChange = { seconds ->
                    settingsViewModel.updateClipboardAutoClearSeconds(seconds)
                },
                passwordDetailSecurityAnalysisEnabled = settings.passwordDetailSecurityAnalysisEnabled,
                onPasswordDetailSecurityAnalysisEnabledChange = { enabled ->
                    settingsViewModel.updatePasswordDetailSecurityAnalysisEnabled(enabled)
                },
                steamMiniProfileBackgroundEnabled = settings.steamMiniProfileBackgroundEnabled,
                onSteamMiniProfileBackgroundEnabledChange = { enabled ->
                    settingsViewModel.updateSteamMiniProfileBackgroundEnabled(enabled)
                },
                passwordSwipeSelectionMode = settings.passwordSwipeSelectionMode,
                onPasswordSwipeSelectionModeChange = { mode ->
                    settingsViewModel.updatePasswordSwipeSelectionMode(mode)
                },
                passwordCardDisplayMode = settings.passwordCardDisplayMode,
                onPasswordCardDisplayModeChange = { mode ->
                    settingsViewModel.updatePasswordCardDisplayMode(mode)
                },
                validatorUnifiedProgressBar = settings.validatorUnifiedProgressBar,
                onValidatorUnifiedProgressBarChange = { mode ->
                    settingsViewModel.updateValidatorUnifiedProgressBar(mode)
                },
                // 通知栏验证器参数
                notificationValidatorEnabled = settings.notificationValidatorEnabled,
                notificationValidatorAutoMatch = settings.notificationValidatorAutoMatch,
                notificationValidatorId = settings.notificationValidatorId,
                totpItems = totpItems,
                onNotificationValidatorEnabledChange = { enabled ->
                    settingsViewModel.updateNotificationValidatorEnabled(enabled)
                },
                onNotificationValidatorAutoMatchChange = { enabled ->
                    settingsViewModel.updateNotificationValidatorAutoMatch(enabled)
                },
                onNotificationValidatorSelected = { id ->
                    settingsViewModel.updateNotificationValidatorId(id)
                }
            )
            }
        }

        composable(
            route = Screen.CommonAccountTemplates.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.CommonAccountTemplatesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.PageAdjustmentCustomization.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.PageAdjustmentCustomizationScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPasswordListCustomization = {
                    navController.navigate(Screen.PasswordListCustomization.route)
                },
                onNavigateToPasswordCardAdjustment = {
                    navController.navigate(Screen.PasswordCardAdjustment.route)
                },
                onNavigateToAuthenticatorCardAdjustment = {
                    navController.navigate(Screen.AuthenticatorCardAdjustment.route)
                },
                onNavigateToPasswordFieldCustomization = {
                    navController.navigate(Screen.PasswordFieldCustomization.route)
                },
                onNavigateToIconSettings = {
                    navController.navigate(Screen.IconSettings.route)
                }
            )
        }

        composable(
            route = Screen.AddButtonCustomization.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.AddButtonCustomizationScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.PasswordListCustomization.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.PasswordListCustomizationScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.PasswordCardAdjustment.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.PasswordCardAdjustmentScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.AuthenticatorCardAdjustment.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.AuthenticatorCardAdjustmentScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.IconSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.IconSettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        // 添加密码页面字段定制页面
        composable(
            route = Screen.PasswordFieldCustomization.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.PasswordFieldCustomizationScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(
            route = Screen.SyncBackup.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.SyncBackupScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToExportData = {
                    navController.navigate(Screen.ExportData.route)
                },
                onNavigateToImportData = {
                    navController.navigate(Screen.ImportData.route)
                },
                onNavigateToWebDav = {
                    navController.navigate(Screen.WebDavBackup.route)
                },
                onNavigateToOneDrive = {
                    navController.navigate(Screen.OneDriveBackup.route)
                },
                onNavigateToDedupEngine = {
                    navController.navigate(Screen.DedupEngine.route)
                },
                onNavigateToLocalKeePass = {
                    navController.navigate(Screen.LocalKeePass.route)
                },
                onNavigateToMdbx = {
                    navController.navigate(Screen.MdbxManager.createRoute()) {
                        popUpTo(Screen.MdbxManager.routePattern) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToBitwarden = {
                    navController.navigate(Screen.BitwardenSettings.route)
                },
                isPlusActivated = settingsViewModel.settings.collectAsState().value.isPlusActivated
            )
            }
        }

        composable(
            route = Screen.DedupEngine.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val dedupPasskeyRepository = remember {
                PasskeyRepository(database.passkeyDao(), mdbxRepository, context.applicationContext)
            }
            val dedupViewModel: DedupEngineViewModel = viewModel {
                DedupEngineViewModel(
                    mergeService = DedupMergeService(
                        passwordRepository = repository,
                        secureItemRepository = secureItemRepository,
                        passkeyRepository = dedupPasskeyRepository,
                        customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(database.customFieldDao()),
                        localKeePassDatabaseDao = database.localKeePassDatabaseDao(),
                        localMdbxDatabaseDao = database.localMdbxDatabaseDao(),
                        bitwardenVaultDao = database.bitwardenVaultDao(),
                        securityManager = securityManager
                    )
                )
            }
            val uiState by dedupViewModel.uiState.collectAsState()
            val dedupLifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(dedupLifecycleOwner, dedupViewModel) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        dedupViewModel.refresh()
                    }
                }
                dedupLifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    dedupLifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            takagi.ru.monica.ui.screens.DedupEngineScreen(
                uiState = uiState,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRefresh = {
                    dedupViewModel.refresh()
                },
                onToggleSource = { sourceKey ->
                    dedupViewModel.toggleMergeSource(sourceKey)
                },
                onSelectAllSources = {
                    dedupViewModel.selectAllSources()
                },
                onClearSources = {
                    dedupViewModel.clearSources()
                },
                onSelectTarget = { target ->
                    dedupViewModel.selectMergeTarget(target)
                },
                onCreateMdbxTarget = {
                    navController.navigate(Screen.MdbxLocalCreate.route)
                },
                onConflictPolicyChange = { policy ->
                    dedupViewModel.updateConflictPolicy(policy)
                },
                onExecuteMerge = {
                    dedupViewModel.executeMerge()
                },
                onCancelMerge = {
                    dedupViewModel.cancelMerge()
                },
                onConsumeMessage = {
                    dedupViewModel.consumeMessage()
                }
            )
        }

        composable(
            route = Screen.LocalKeePass.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.LocalKeePassScreen(
                viewModel = localKeePassViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateNativeEntry = { route ->
                    when (route) {
                        is KeePassNativeResolvedRoute.Password ->
                            navController.navigate(Screen.PasswordDetail.createRoute(route.id)) { launchSingleTop = true }
                        is KeePassNativeResolvedRoute.Totp ->
                            navController.navigate(Screen.AddEditTotp.createRoute(route.id)) { launchSingleTop = true }
                        is KeePassNativeResolvedRoute.Note ->
                            navController.navigate(Screen.NoteDetail.createRoute(route.id)) { launchSingleTop = true }
                        is KeePassNativeResolvedRoute.BankCard ->
                            navController.navigate("bank_card_detail/${route.id}") { launchSingleTop = true }
                        is KeePassNativeResolvedRoute.Document ->
                            navController.navigate(Screen.DocumentDetail.createRoute(route.id)) { launchSingleTop = true }
                        is KeePassNativeResolvedRoute.Passkey ->
                            navController.navigate(Screen.PasskeyDetail.createRoute(route.recordId)) { launchSingleTop = true }
                        KeePassNativeResolvedRoute.Generic -> Unit
                    }
                }
            )
        }
        
        composable(
            route = Screen.ColorSchemeSelection.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            takagi.ru.monica.ui.screens.ColorSchemeSelectionScreen(
                settingsViewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToCustomColors = {
                    navController.navigate(Screen.CustomColorSettings.route)
                }
            )
            }
        }
        
        composable(
            route = Screen.CustomColorSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            takagi.ru.monica.ui.screens.CustomColorSettingsScreen(
                settingsViewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        // 添加生成器页面的导航支持
        composable(Screen.Generator.route) {
            takagi.ru.monica.ui.screens.GeneratorScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                passwordViewModel = viewModel
            )
        }
        
        // 权限管理页面
        composable(
            route = Screen.PermissionManagement.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            PermissionManagementScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
            }
        }

        composable(
            route = Screen.MonicaPlus.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val settings by settingsViewModel.settings.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                takagi.ru.monica.ui.LocalAnimatedVisibilityScope provides this
            ) {
            MonicaPlusScreen(
                isPlusActivated = settings.isPlusActivated,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPayment = {
                    navController.navigate(Screen.Payment.route)
                },
                onDeactivatePlus = {
                    settingsViewModel.updatePlusActivated(false)
                }
            )
        }
        }

        composable(
            route = Screen.Payment.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {

            PaymentScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onActivatePlus = {
                    settingsViewModel.updatePlusActivated(true)
                }
            )
        }
        
        // Bitwarden 登录页面
        composable(
            route = Screen.BitwardenLogin.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = 
                androidx.lifecycle.viewmodel.compose.viewModel()
            val bitwardenTotpSuggestions by totpViewModel.parsedTotpItems.collectAsState()
            takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen(
                viewModel = bitwardenViewModel,
                totpSuggestions = bitwardenTotpSuggestions,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLoginSuccess = {
                    navController.navigate(Screen.BitwardenSettings.route) {
                        popUpTo(Screen.BitwardenLogin.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Bitwarden 设置/管理页面
        composable(
            route = Screen.BitwardenSettings.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = 
                androidx.lifecycle.viewmodel.compose.viewModel()
            takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen(
                viewModel = bitwardenViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.BitwardenLogin.route)
                },
                onNavigateToVault = { vaultId ->
                    // 未来可以添加 Vault 详情页面
                    // 目前保持空实现
                },
                onNavigateToSyncQueue = {
                    navController.navigate(Screen.SyncQueue.route)
                }
            )
        }
        
        // 同步队列管理页面
        composable(
            route = Screen.SyncQueue.route,
            enterTransition = { easyNotesScreenEnter() },
            exitTransition = { easyNotesScreenExit() },
            popEnterTransition = { easyNotesScreenEnter() },
            popExitTransition = { easyNotesScreenExit() }
        ) {
            // Legacy queue page placeholder; Bitwarden sync now runs through SyncTaskRunner.
            var queueItems by remember { mutableStateOf(emptyList<takagi.ru.monica.bitwarden.ui.SyncQueueItem>()) }
            
            takagi.ru.monica.bitwarden.ui.SyncQueueScreen(
                queueItems = queueItems,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRetryItem = { item ->
                    // TODO: 实现重试逻辑
                },
                onDeleteItem = { item ->
                    // TODO: 实现删除逻辑
                    queueItems = queueItems.filter { it.id != item.id }
                },
                onRetryAll = {
                    // TODO: 实现全部重试逻辑
                },
                onClearCompleted = {
                    // TODO: 实现清除已完成逻辑
                    queueItems = queueItems.filter { 
                        it.status != takagi.ru.monica.bitwarden.sync.SyncStatus.SYNCED 
                    }
                }
            )
            }
        }

            if (showAuthTransitionGate) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private fun launchSystemFidoQrIntent(context: Context, rawQrData: String): Int? {
    val qrData = rawQrData.trim()
    if (qrData.isBlank()) return R.string.passkey_qr_invalid
    val uri = runCatching { Uri.parse(qrData) }.getOrNull()
        ?: return R.string.passkey_qr_invalid
    val scheme = uri.scheme.orEmpty()
    if (scheme.isBlank()) return R.string.passkey_qr_invalid

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
    }.fold(
        onSuccess = { null },
        onFailure = { R.string.passkey_qr_open_failed }
    )
}

/**
 * 安全的视图填充函数，添加错误处理和降级方案
 */
private fun inflateViewSafely(
    layoutInflater: LayoutInflater,
    layoutId: Int,
    parent: ViewGroup?,
    attachToRoot: Boolean
): android.view.View? {
    try {
        return layoutInflater.inflate(layoutId, parent, attachToRoot)
    } catch (e: Exception) {
        Log.e("MainActivity", "Error inflating layout: $layoutId", e)
        // 返回一个简单的降级视图
        return TextView(parent?.context ?: layoutInflater.context).apply {
            text = "无法加载视图"
            gravity = Gravity.CENTER
        }
    }
}

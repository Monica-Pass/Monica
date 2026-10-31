package takagi.ru.monica.autofill_ng

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

class AutofillSaveTransparentActivity : androidx.fragment.app.FragmentActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_WEBSITE = "website"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val RESULT_SAVED = Activity.RESULT_FIRST_USER
    }

    private lateinit var autofillPreferences: AutofillPreferences
    @Volatile
    private var didSave = false
    private var saveRequestId = "direct"

    private fun recordSaveEvent(event: AutofillSaveDiagnostics.Event) {
        AutofillSaveDiagnostics.record(this, saveRequestId, event,
            compatibilityMode = intent.getBooleanExtra(AutofillSaveLaunch.EXTRA_COMPATIBILITY_MODE, false))
    }

    private fun closeSave(event: AutofillSaveDiagnostics.Event) {
        recordSaveEvent(event)
        finish()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        AutofillSaveDiagnostics.record(this,
            intent.getStringExtra(AutofillSaveLaunch.EXTRA_REQUEST_ID).orEmpty(),
            AutofillSaveDiagnostics.Event.ACTIVITY_NEW_INTENT)
        setIntent(intent)
        recreate()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        saveRequestId = intent.getStringExtra(AutofillSaveLaunch.EXTRA_REQUEST_ID).orEmpty()
        takagi.ru.monica.autofill_ng.core.AutofillLogger.initialize(applicationContext)
        recordSaveEvent(AutofillSaveDiagnostics.Event.ACTIVITY_CREATED)

        autofillPreferences = AutofillPreferences(applicationContext)
        val settingsManager = SettingsManager(applicationContext)

        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val website = intent.getStringExtra(EXTRA_WEBSITE).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val appName = resolveAppName(packageName)

        val securityManager = SecurityManager(applicationContext)
        val database = PasswordDatabase.getDatabase(applicationContext)
        val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(
            context = applicationContext,
            database = database,
            securityManager = securityManager
        )
        val repository = PasswordRepository(
            passwordEntryDao = database.passwordEntryDao(),
            categoryDao = database.categoryDao(),
            bitwardenFolderDao = database.bitwardenFolderDao(),
            secureItemDao = database.secureItemDao(),
            passkeyDao = database.passkeyDao(),
            passwordArchiveSyncMetaDao = database.passwordArchiveSyncMetaDao(),
            passwordHistoryDao = database.passwordHistoryDao(),
            mdbxRepository = mdbxRepository,
        )
        val secureItemRepository = SecureItemRepository(
            database.secureItemDao(),
            mdbxRepository,
            securityManager::decryptDataIfMonicaCiphertext
        )
        val customFieldRepository = CustomFieldRepository(database.customFieldDao())
        val application = applicationContext as Application

        val lockGenerationAtOpen = SessionManager.lockGeneration.value
        setContent {
            val lockGeneration by SessionManager.lockGeneration.collectAsState()
            LaunchedEffect(lockGeneration) {
                if (lockGeneration != lockGenerationAtOpen) closeSave(AutofillSaveDiagnostics.Event.LOCK_CHANGED)
            }
            val unlocked by SessionManager.isUnlocked.collectAsState()
            var authorized by remember { mutableStateOf(unlocked || !securityManager.isMasterPasswordSet()) }
            var wasUnlocked by remember { mutableStateOf(unlocked) }
            LaunchedEffect(Unit) {
                val current = settingsManager.settingsFlow.first()
                val grantContext = takagi.ru.monica.autofill_ng.auth.AutofillGrantContext(packageName, website.takeIf { it.isNotBlank() }, null, null)
                if (!authorized && current.autofillKeepUnlocked && takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants.isGranted(grantContext)) {
                    authorized = true
                }
                if (!authorized && current.biometricEnabled) {
                    val biometrics = takagi.ru.monica.utils.BiometricAuthHelper(this@AutofillSaveTransparentActivity)
                    if (biometrics.isBiometricAvailable()) runCatching {
                        biometrics.authenticate(activity = this@AutofillSaveTransparentActivity,
                            title = getString(R.string.autofill_auth_title), subtitle = getString(R.string.autofill_auth_subtitle),
                            description = getString(R.string.autofill_auth_description), negativeButtonText = getString(R.string.use_password),
                            onSuccess = { if (securityManager.unlockVaultWithBiometric()) authorized = true },
                            onError = { _, _ -> }, onCancel = { closeSave(AutofillSaveDiagnostics.Event.AUTH_CANCELED) }, onNegativeButton = { })
                    }
                }
            }
            LaunchedEffect(unlocked) {
                if (wasUnlocked && !unlocked) closeSave(AutofillSaveDiagnostics.Event.SESSION_LOCKED)
                if (unlocked) wasUnlocked = true
            }
            // Never enumerate saved accounts or open their editor while the vault is locked.
            DisposableEffect(Unit) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: android.content.Intent?) {
                        if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) closeSave(AutofillSaveDiagnostics.Event.SCREEN_OFF)
                    }
                }
                androidx.core.content.ContextCompat.registerReceiver(this@AutofillSaveTransparentActivity, receiver,
                    android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose { unregisterReceiver(receiver) }
            }
            if (!authorized) {
                LaunchedEffect(Unit) { recordSaveEvent(AutofillSaveDiagnostics.Event.AUTH_READY) }
                MonicaPasswordDialogAuthScreen(
                    settingsFlow = settingsManager.settingsFlow,
                    appName = getString(R.string.app_name), title = getString(R.string.verify_identity),
                    subtitle = getString(R.string.autofill_auth_subtitle), passwordLabel = getString(R.string.master_password),
                    description = getString(R.string.enter_master_password), confirmText = getString(R.string.confirm),
                    cancelText = getString(R.string.cancel), emptyError = getString(R.string.current_password_required),
                    unsupportedCharacterError = getString(R.string.error_password_contains_unsupported_characters),
                    minLengthError = getString(R.string.error_password_too_short), incorrectError = getString(R.string.password_incorrect),
                    verifyPassword = { securityManager.unlockVaultWithPassword(it) },
                    onSuccess = { authorized = true }, onCancel = { closeSave(AutofillSaveDiagnostics.Event.AUTH_CANCELED) },
                )
                return@setContent
            }
            val settings by settingsManager.settingsFlow.collectAsState(
                initial = takagi.ru.monica.data.AppSettings()
            )
            val autoSaveAppInfoEnabled by autofillPreferences.isAutoSaveAppInfoEnabled.collectAsState(initial = true)
            val autoSaveWebsiteInfoEnabled by autofillPreferences.isAutoSaveWebsiteInfoEnabled.collectAsState(initial = true)
            val smartTitleGenerationEnabled by autofillPreferences.isSmartTitleGenerationEnabled.collectAsState(initial = true)
            val initialTarget by produceState<AutofillSaveInitialTarget?>(
                initialValue = null,
                key1 = settingsManager,
                key2 = database
            ) {
                val settingsSnapshot = settingsManager.settingsFlow.first()
                val mdbxDatabases = database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()
                value = resolveAutofillSaveInitialTarget(settingsSnapshot, mdbxDatabases).also { target ->
                    MdbxDiagLogger.append(
                        "[MDBX][autofill-save-open] source=transparent target=${target.diagnosticLabel()} mdbxDatabases=${target.mdbxDatabasesFallback.size}"
                    )
                }
            }

            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val effectiveWebsite = if (autoSaveWebsiteInfoEnabled) website else ""
            val effectivePackageName = if (autoSaveAppInfoEnabled) packageName else ""
            val effectiveAppName = if (autoSaveAppInfoEnabled) appName else ""
            val initialTitle = remember(
                smartTitleGenerationEnabled,
                effectiveWebsite,
                effectiveAppName,
                username,
                packageName,
            ) {
                if (smartTitleGenerationEnabled) {
                    when {
                        effectiveWebsite.isNotBlank() -> effectiveWebsite
                        effectiveAppName.isNotBlank() -> effectiveAppName
                        username.isNotBlank() -> username
                        packageName.isNotBlank() -> packageName.substringAfterLast('.')
                        else -> ""
                    }
                } else {
                    ""
                }
            }

            val passwordViewModel: PasswordViewModel = viewModel(
                factory = remember(repository, securityManager, secureItemRepository, customFieldRepository, database) {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            if (modelClass.isAssignableFrom(PasswordViewModel::class.java)) {
                                return PasswordViewModel(
                                    repository = repository,
                                    securityManager = securityManager,
                                    secureItemRepository = secureItemRepository,
                                    customFieldRepository = customFieldRepository,
                                    context = applicationContext,
                                    localKeePassDatabaseDao = database.localKeePassDatabaseDao(),
                                    strings = AppLocaleStringResolver(this@AutofillSaveTransparentActivity),
                                ) as T
                            }
                            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                        }
                    }
                }
            )

            val localKeePassViewModel: LocalKeePassViewModel = viewModel(
                factory = remember(database, securityManager, application) {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            if (modelClass.isAssignableFrom(LocalKeePassViewModel::class.java)) {
                                return LocalKeePassViewModel(
                                    application = application,
                                    dao = database.localKeePassDatabaseDao(),
                                    securityManager = securityManager,
                                ) as T
                            }
                            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                        }
                    }
                }
            )

            val candidates by produceState<List<AutofillSaveCandidate>?>(initialValue = null) {
                value = try {
                    withContext(Dispatchers.IO) {
                        val keepassNames = database.localKeePassDatabaseDao().getAllDatabases().first().associate { it.id to it.name }
                        val mdbxNames = database.localMdbxDatabaseDao().getAllDatabasesSnapshot().associate { it.id to it.name }
                        val matches = repository.getAllPasswordEntries().first().sortedBy { it.id }.mapNotNull { entry ->
                            if (!AutofillSaveMatchPolicy.matchesTarget(entry, packageName, website)) return@mapNotNull null
                            val account = AutofillSecretResolver.decryptPasswordOrNull(securityManager, entry.username)
                            if (!AutofillSaveMatchPolicy.matches(entry, account, username, packageName, website)) return@mapNotNull null
                            AutofillSaveCandidate(entry.id, entry.title, account.orEmpty(), when {
                                entry.bitwardenVaultId != null -> "Bitwarden"
                                entry.keepassDatabaseId != null -> (keepassNames[entry.keepassDatabaseId] ?: "KeePass") + entry.keepassGroupPath?.let { " · $it" }.orEmpty()
                                entry.mdbxDatabaseId != null -> mdbxNames[entry.mdbxDatabaseId] ?: "MDBX"
                                else -> getString(R.string.autofill_save_local_source)
                            })
                        }
                        val duplicates = matches.groupBy { Triple(it.title, it.account, it.source) }.values.filter { it.size > 1 }
                            .flatMap { group -> group.mapIndexed { index, item -> item.id to index + 1 } }.toMap()
                        matches.map { item -> duplicates[item.id]?.let { number ->
                            item.copy(source = "${item.source} · ${getString(R.string.project_credential_password_number, number)}")
                        } ?: item }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { emptyList() }
            }
            var reviewed by remember { mutableStateOf(false) }
            var updateId by remember { mutableStateOf<Long?>(null) }

            takagi.ru.monica.utils.ScreenshotProtection(enabled = settings.screenshotProtectionEnabled)

            MonicaTheme(
                darkTheme = darkTheme,
                colorScheme = settings.colorScheme,
                customPrimaryColor = settings.customPrimaryColor,
                customSecondaryColor = settings.customSecondaryColor,
                customTertiaryColor = settings.customTertiaryColor,
                customNeutralColor = settings.customNeutralColor,
                customNeutralVariantColor = settings.customNeutralVariantColor,
            ) {
                val matches = candidates
                if (matches == null) {
                    Box(Modifier.then(Modifier.padding(24.dp)), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading))
                    }
                    return@MonicaTheme
                }
                if (matches.isNotEmpty() && !reviewed) {
                    LaunchedEffect(Unit) { recordSaveEvent(AutofillSaveDiagnostics.Event.REVIEW_READY) }
                    AutofillSaveReview(matches, listOf(website.ifBlank { appName }, username).filter { it.isNotBlank() }.joinToString(" · "),
                        onSelect = { updateId = it; reviewed = true }, onCancel = { closeSave(AutofillSaveDiagnostics.Event.REVIEW_CANCELED) })
                    return@MonicaTheme
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                    val resolvedInitialTarget = initialTarget
                    if (resolvedInitialTarget != null) {
                        LaunchedEffect(Unit) { recordSaveEvent(AutofillSaveDiagnostics.Event.EDITOR_READY) }
                        AddEditPasswordScreen(
                            viewModel = passwordViewModel,
                            localKeePassViewModel = localKeePassViewModel,
                            mdbxDatabasesFallback = resolvedInitialTarget.mdbxDatabasesFallback,
                            passwordId = updateId,
                            autofillPasswordUpdate = password.takeIf { updateId != null },
                            initialDraft = AddEditPasswordInitialDraft(
                                title = initialTitle,
                                website = effectiveWebsite,
                                username = username,
                                password = password,
                                appPackageName = effectivePackageName,
                                appName = effectiveAppName,
                            ),
                            forceShowAppBinding = true,
                            initialMdbxDatabaseId = resolvedInitialTarget.mdbxDatabaseId,
                            initialMdbxFolderId = resolvedInitialTarget.mdbxFolderId,
                            onSaveCompleted = { savedId ->
                                didSave = true
                                recordSaveEvent(AutofillSaveDiagnostics.Event.SAVE_COMPLETED)
                                MdbxDiagLogger.append(
                                    "[MDBX][autofill-save-complete] source=transparent target=${resolvedInitialTarget.diagnosticLabel()} roomId=${savedId ?: "-"}"
                                )
                                setResult(RESULT_SAVED)
                            },
                            onNavigateBack = {
                                if (!didSave) {
                                    setResult(Activity.RESULT_CANCELED)
                                }
                                closeSave(AutofillSaveDiagnostics.Event.EDITOR_CLOSED)
                            },
                        )
                    }

                    }
                    TextButton(
                        onClick = {
                            blockCurrentTarget(packageName, website)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Block,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.autofill_never_for_site))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recordSaveEvent(AutofillSaveDiagnostics.Event.ACTIVITY_RESUMED)
    }

    override fun onDestroy() {
        recordSaveEvent(AutofillSaveDiagnostics.Event.ACTIVITY_DESTROYED)
        super.onDestroy()
    }

    private fun blockCurrentTarget(packageName: String, website: String) {
        lifecycleScope.launch {
            runCatching {
                autofillPreferences.addSaveBlockedTarget(
                    packageName = packageName.takeIf { it.isNotBlank() },
                    webDomain = website.takeIf { it.isNotBlank() },
                )
            }
            setResult(Activity.RESULT_CANCELED)
            closeSave(AutofillSaveDiagnostics.Event.TARGET_BLOCKED)
        }
    }

    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault("")
    }
}

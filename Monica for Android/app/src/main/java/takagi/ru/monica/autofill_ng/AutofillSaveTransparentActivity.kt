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
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

class AutofillSaveTransparentActivity : ComponentActivity() {

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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

        setContent {
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
                Box {
                    val resolvedInitialTarget = initialTarget
                    if (resolvedInitialTarget != null) {
                        AddEditPasswordScreen(
                            viewModel = passwordViewModel,
                            localKeePassViewModel = localKeePassViewModel,
                            mdbxDatabasesFallback = resolvedInitialTarget.mdbxDatabasesFallback,
                            passwordId = null,
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
                                MdbxDiagLogger.append(
                                    "[MDBX][autofill-save-complete] source=transparent target=${resolvedInitialTarget.diagnosticLabel()} roomId=${savedId ?: "-"}"
                                )
                                setResult(RESULT_SAVED)
                            },
                            onNavigateBack = {
                                if (!didSave) {
                                    setResult(Activity.RESULT_CANCELED)
                                }
                                finish()
                            },
                        )
                    }

                    TextButton(
                        onClick = {
                            blockCurrentTarget(packageName, website)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
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

    private fun blockCurrentTarget(packageName: String, website: String) {
        lifecycleScope.launch {
            runCatching {
                autofillPreferences.addSaveBlockedTarget(
                    packageName = packageName.takeIf { it.isNotBlank() },
                    webDomain = website.takeIf { it.isNotBlank() },
                )
            }
            setResult(Activity.RESULT_CANCELED)
            finish()
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

package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.compose.ui.unit.dp
import kotlinx.parcelize.Parcelize
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.builder.AutofillDatasetBuilder
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.ui.*
import takagi.ru.monica.autofill_ng.utils.SmartCopyNotificationHelper
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.GeneratorPreferences
import takagi.ru.monica.data.toSymbolPasswordGeneratorOptions
import takagi.ru.monica.data.GeneratorPreferencesManager
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.isLocalPasswordOwnership
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.service.MonicaAccessibilityService
import takagi.ru.monica.ui.theme.MonicaTheme
import androidx.compose.foundation.isSystemInDarkTheme
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.displayFullName
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.components.PasswordVerificationContent
import takagi.ru.monica.ui.PasswordListInitialLoadingIndicator
import takagi.ru.monica.ui.base.BaseMonicaActivity
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditBillingAddressScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.util.PasswordGenerator
import takagi.ru.monica.utils.AppLauncherIconManager
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keyguard 风格的全屏自动填充选择器
 * 
 * 特点:
 * - 全屏界面（非 BottomSheet）
 * - 顶部显示捕获的表单数据
 * - 支持搜索和筛选
 * - 支持新建密码
 * - Dropdown 菜单选择操作
 */
class AutofillPickerActivityV2 : BaseMonicaActivity() {
    private val passwordAutofillInFlight = AtomicBoolean(false)
    private var passwordAutofillPreparationKey: Long? = null
    private var passwordAutofillPreparation: Deferred<PasswordAutofillPreparation>? = null
    
    companion object {
        private const val EXTRA_ARGS = "extra_args"
        const val EXTRA_MANUAL_MODE = "extra_manual_mode"
        const val EXTRA_MANUAL_TARGET_PACKAGE = "extra_manual_target_package"
        const val EXTRA_IME_MODE = "extra_ime_mode"
        private const val DUPLICATE_LAUNCH_WINDOW_MS = 1500L
        private const val MANUAL_ACCESSIBILITY_FILL_DELAY_MS = 450L
        private const val MANUAL_ACCESSIBILITY_RETRY_DELAY_MS = 250L
        private const val MANUAL_ACCESSIBILITY_MAX_ATTEMPTS = 8
        @Volatile
        private var lastLaunchSignature: String? = null
        @Volatile
        private var lastLaunchAtMs: Long = 0L
        
        /**
         * 创建启动 Intent（契约保持不变，确保 Service 兼容）
         */
        fun getIntent(context: Context, args: Args): Intent {
            return Intent(context, AutofillPickerActivityV2::class.java).apply {
                putExtra(EXTRA_ARGS, args)
            }
        }
        
        /**
         * 创建测试 Intent（用于开发者调试入口）
         */
        fun getTestIntent(context: Context): Intent {
            val testArgs = Args(
                applicationId = "com.test.autofill",
                webDomain = "example.com",
                capturedUsername = "test_user",
                capturedPassword = null,
                isSaveMode = false
            )
            return getIntent(context, testArgs)
        }

        private fun buildLaunchSignature(args: Args): String {
            val idCount = args.autofillIds?.size ?: 0
            val hintCount = args.autofillHints?.size ?: 0
            val suggestedCount = args.suggestedPasswordIds?.size ?: 0
            return buildString {
                append(args.applicationId.orEmpty())
                append('|')
                append(args.webDomain.orEmpty())
                append('|')
                append(args.interactionIdentifier.orEmpty())
                append('|')
                append(args.fieldSignatureKey.orEmpty())
                append('|')
                append(idCount)
                append('|')
                append(hintCount)
                append('|')
                append(suggestedCount)
                append('|')
                append(args.responseAuthMode)
                append('|')
                append(args.isSaveMode)
            }
        }

        @Synchronized
        private fun shouldSuppressDuplicateLaunch(args: Args): Boolean {
            val now = System.currentTimeMillis()
            val signature = buildLaunchSignature(args)
            val isDuplicate = lastLaunchSignature == signature &&
                now - lastLaunchAtMs <= DUPLICATE_LAUNCH_WINDOW_MS
            lastLaunchSignature = signature
            lastLaunchAtMs = now
            return isDuplicate
        }
    }
    
    @Parcelize
    data class Args(
        val applicationId: String? = null,
        val webDomain: String? = null,
        val webScheme: String? = null,
        val interactionIdentifier: String? = null,
        val interactionIdentifierAliases: ArrayList<String>? = null,
        val capturedUsername: String? = null,
        val capturedPassword: String? = null,
        val autofillIds: ArrayList<AutofillId>? = null,
        val autofillHints: ArrayList<String>? = null,
        val suggestedPasswordIds: LongArray? = null,
        val isSaveMode: Boolean = false,
        val fieldSignatureKey: String? = null,
        val responseAuthMode: Boolean = false,
        val rememberLastFilled: Boolean = true,
    ) : Parcelable {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Args
            return applicationId == other.applicationId &&
                   webDomain == other.webDomain &&
                   isSaveMode == other.isSaveMode
        }
        override fun hashCode(): Int {
            var result = applicationId?.hashCode() ?: 0
            result = 31 * result + (webDomain?.hashCode() ?: 0)
            result = 31 * result + isSaveMode.hashCode()
            return result
        }
    }

    private sealed class PasswordAutofillPreparation {
        data class Manual(
            val accountValue: String,
            val passwordValue: String
        ) : PasswordAutofillPreparation()

        data class Fill(
            val accountValue: String,
            val filledValues: Map<AutofillId, String>,
            val filledCount: Int,
            val strictFilledCount: Int,
            val fallbackFilledCount: Int,
            val hasOtpHint: Boolean,
            val otpResolved: Boolean,
            val allowAccountInEmailField: Boolean,
            val unmatchedHintPreview: List<String>,
            val usedFallback: Boolean
        ) : PasswordAutofillPreparation()

        data class Cancel(val logMessage: String) : PasswordAutofillPreparation()
    }
    
    private val args by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ARGS, Args::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ARGS)
        } ?: Args()
    }
    
    private val explicitManualMode by lazy {
        intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)
    }

    private val manualTargetPackage by lazy {
        intent.getStringExtra(EXTRA_MANUAL_TARGET_PACKAGE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private val imeMode by lazy {
        intent.getBooleanExtra(EXTRA_IME_MODE, false)
    }

    private val manualModeReason by lazy {
        when {
            manualTargetPackage != null -> "active_fill_notification"
            explicitManualMode -> "explicit_manual_extra"
            !args.fieldSignatureKey.isNullOrBlank() -> "framework_context_field_signature"
            !args.applicationId.isNullOrBlank() -> "framework_context_application_id"
            !args.webDomain.isNullOrBlank() -> "framework_context_web_domain"
            args.responseAuthMode -> "framework_context_response_auth"
            args.isSaveMode -> "framework_context_save_mode"
            args.autofillIds.isNullOrEmpty() -> "no_framework_context_and_no_ids"
            else -> "autofill_ids_present"
        }
    }

    // 手动模式：仅在明确的手动入口中启用。
    // 某些系统/应用链路会丢失 AutofillId，但仍然属于真实的自动填充请求，
    // 这类场景不能退化成手动模式，否则会导致来源显示错误且无法标记非自动填充。
    private val isManualMode by lazy {
        if (explicitManualMode) {
            true
        } else {
            val hasFrameworkAutofillContext =
                !args.fieldSignatureKey.isNullOrBlank() ||
                    !args.applicationId.isNullOrBlank() ||
                    !args.webDomain.isNullOrBlank() ||
                    args.responseAuthMode ||
                    args.isSaveMode
            !hasFrameworkAutofillContext && args.autofillIds.isNullOrEmpty()
        }
    }
    

    
    // attachBaseContext 已由 BaseMonicaActivity 统一处理
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // BaseMonicaActivity 已调用 enableEdgeToEdge()
        AutofillLogger.initialize(applicationContext)
        val launchedFromAutofillFramework = !args.autofillIds.isNullOrEmpty()
        if (!launchedFromAutofillFramework && shouldSuppressDuplicateLaunch(args)) {
            AutofillLogger.w("PICKER", "Suppress duplicate picker launch within ${DUPLICATE_LAUNCH_WINDOW_MS}ms")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val idCount = args.autofillIds?.size ?: 0
        val hintCount = args.autofillHints?.size ?: 0
        val suggestedCount = args.suggestedPasswordIds?.size ?: 0
        AutofillLogger.i(
            "PICKER",
            "Picker opened: saveMode=${args.isSaveMode}, responseAuth=${args.responseAuthMode}, ids=$idCount, hints=$hintCount",
            metadata = mapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "manualMode" to isManualMode,
                "manualReason" to manualModeReason,
                "manualExtra" to explicitManualMode,
                "launchedFromFramework" to launchedFromAutofillFramework,
                "idCount" to idCount,
                "hintCount" to hintCount,
                "suggestedCount" to suggestedCount,
                "hintPreview" to (args.autofillHints?.take(6)?.joinToString(",") ?: "none"),
                "applicationId" to (args.applicationId ?: "none"),
                "webDomain" to (args.webDomain ?: "none"),
                "responseAuth" to args.responseAuthMode,
                "saveMode" to args.isSaveMode,
                "interactionIdPresent" to !args.interactionIdentifier.isNullOrBlank(),
                "fieldSignaturePresent" to !args.fieldSignatureKey.isNullOrBlank()
            )
        )
        if (!explicitManualMode && idCount == 0) {
            AutofillLogger.w(
                "PICKER",
                "Opened without AutofillId in non-manual entry path",
                metadata = mapOf(
                    "hintCount" to hintCount,
                    "responseAuth" to args.responseAuthMode,
                    "applicationId" to (args.applicationId ?: "none"),
                    "webDomain" to (args.webDomain ?: "none"),
                    "fieldSignaturePresent" to !args.fieldSignatureKey.isNullOrBlank(),
                    "manualReason" to manualModeReason,
                )
            )
        }
        if (hintCount > 0 && idCount != hintCount) {
            AutofillLogger.w(
                "PICKER",
                "Autofill IDs and hints size mismatch",
                metadata = mapOf("idCount" to idCount, "hintCount" to hintCount)
            )
        }
        
        val database = PasswordDatabase.getDatabase(applicationContext)
        val repository = PasswordRepository(database.passwordEntryDao())
        val localKeePassDao = database.localKeePassDatabaseDao()
        val securityManager = SecurityManager(applicationContext)
        // settingsManager 已由 BaseMonicaActivity 初始化
        val localSettingsManager = settingsManager
        val generatorPreferencesManager = GeneratorPreferencesManager(applicationContext)

        runCatching {
            val autoLockMinutes = runBlocking {
                localSettingsManager.settingsFlow.first().autoLockMinutes
            }
            SessionManager.updateAutoLockTimeout(autoLockMinutes)
        }.onFailure { error ->
            AutofillLogger.w(
                "PICKER",
                "Failed to sync auto-lock timeout before verification: ${error.message}"
            )
        }
        
        // 主应用已解锁时可直接进入；否则在自动填充页内复用同款验证界面，
        // 但验证结果仅用于本次自动填充，不回写主应用共享会话。
        val startupSettings = cachedSettings ?: runBlocking { localSettingsManager.settingsFlow.first() }
        val developerBypass = DeveloperVerificationPolicy.bypassesIdentityVerification(startupSettings)
        if (developerBypass) {
            securityManager.unlockVaultForDeveloperBypass(startupSettings.autoLockMinutes)
        }
        val canOpenPicker = developerBypass || securityManager.canRestoreMainAppSession(
            applicationContext,
            startupSettings.autoLockMinutes
        )
        
        setContent {
            val generatorPreferences by produceState<GeneratorPreferences?>(initialValue = null) {
                value = withContext(Dispatchers.IO) { generatorPreferencesManager.load() }
            }
            // 读取截图保护设置（截图保护已由 BaseMonicaActivity 统一处理）
            val settings by localSettingsManager.settingsFlow.collectAsState(
                initial = takagi.ru.monica.data.AppSettings()
            )
            
            // 获取 KeePass 数据库列表
            val keepassDatabases by localKeePassDao.getAllDatabases().collectAsState(initial = emptyList())
            
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MonicaTheme(
                darkTheme = darkTheme,
                colorScheme = settings.colorScheme,
                customPrimaryColor = settings.customPrimaryColor,
                customSecondaryColor = settings.customSecondaryColor,
                customTertiaryColor = settings.customTertiaryColor,
                customNeutralColor = settings.customNeutralColor,
                customNeutralVariantColor = settings.customNeutralVariantColor
            ) {
                AutofillPickerContent(
                    args = args,
                    repository = repository,
                    securityManager = securityManager,
                    keepassDatabases = keepassDatabases,
                    generatorPreferences = generatorPreferences,
                    canSkipVerification = canOpenPicker,
                    requireAuthentication = settings.autofillAuthRequired &&
                        DeveloperVerificationPolicy.requiresIdentityVerification(settings),
                    biometricEnabled = settings.biometricEnabled,
                    autoLockMinutes = settings.autoLockMinutes,
                    iconCardsEnabled = settings.iconCardsEnabled,
                    isManualMode = isManualMode,
                    showTargetContextInManualMode = imeMode,
                    manualModeReason = manualModeReason,
                    onPrepareAutofill = ::prewarmPasswordAutofill,
                    onAutofill = { password, forceAddUri ->
                        handleAutofill(password, forceAddUri)
                    },
                    onAutofillBankCard = ::handleBankCardAutofill,
                    onAutofillDocument = ::handleDocumentAutofill,
                    onAutofillBillingAddress = ::handleBillingAddressAutofill,
                    onFillGeneratedPassword = ::handleGeneratedPasswordFill,
                    onCopy = ::copyToClipboard,
                    onClose = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onMarkAsNonAutofill = { markCurrentFieldAsNonAutofill() },
                    onSmartCopy = { password, usernameFirst ->
                        handleSmartCopy(password, usernameFirst)
                    }
                )
            }
        }
    }

    override fun shouldEnforceSharedSessionLock(): Boolean {
        return false
    }

    private fun markCurrentFieldAsNonAutofill() {
        val signatureKey = args.fieldSignatureKey?.trim()?.lowercase().orEmpty()
        if (signatureKey.isBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                AutofillPreferences(applicationContext).markFieldSignatureBlocked(
                    signatureKey = signatureKey,
                    packageName = args.applicationId,
                    webDomain = args.webDomain,
                    hints = args.autofillHints?.toList().orEmpty(),
                )
            }.onFailure { error ->
                AutofillLogger.e("PICKER", "Failed to block field signature", error)
            }
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ARGS, Args::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ARGS)
        }
        AutofillLogger.w(
            "PICKER",
            "onNewIntent received while picker is active; reusing current instance",
            metadata = mapOf(
                "newIdCount" to (newArgs?.autofillIds?.size ?: 0),
                "newHintCount" to (newArgs?.autofillHints?.size ?: 0),
                "newSuggestedCount" to (newArgs?.suggestedPasswordIds?.size ?: 0),
                "newSaveMode" to (newArgs?.isSaveMode ?: false),
                "newResponseAuth" to (newArgs?.responseAuthMode ?: false)
            )
        )
    }
    
    private fun handleSmartCopy(password: PasswordEntry, usernameFirst: Boolean) {
        val securityManager = SecurityManager(applicationContext)
        val accountValue = AccountFillPolicy.resolveAccountIdentifier(password, securityManager)
        val decryptedPassword = AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = password.password,
            logTag = "AutofillPickerV2",
        )
        if (decryptedPassword.isNullOrBlank()) {
            android.util.Log.w("AutofillPickerV2", "Smart copy skipped: password decryption unavailable")
            android.widget.Toast.makeText(
                this,
                R.string.autofill_copy_password_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (usernameFirst && accountValue.isBlank()) {
            android.util.Log.w("AutofillPickerV2", "Smart copy skipped: account identifier unavailable")
            android.widget.Toast.makeText(
                this,
                R.string.autofill_copy_account_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!usernameFirst && accountValue.isBlank()) {
            copyToClipboard(getString(R.string.autofill_password), decryptedPassword, true)
            android.widget.Toast.makeText(
                this,
                R.string.autofill_copy_account_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        val queued = if (usernameFirst) {
            // Copy username first, queue password for notification
            val result = SmartCopyNotificationHelper.copyAndQueueNext(
                context = this,
                firstValue = accountValue,
                firstLabel = getString(R.string.autofill_username),
                secondValue = decryptedPassword,
                secondLabel = getString(R.string.autofill_password)
            )
            android.widget.Toast.makeText(this, R.string.username_copied, android.widget.Toast.LENGTH_SHORT).show()
            result
        } else {
            // Copy password first, queue username for notification
            val result = SmartCopyNotificationHelper.copyAndQueueNext(
                context = this,
                firstValue = decryptedPassword.orEmpty(),
                firstLabel = getString(R.string.autofill_password),
                secondValue = accountValue,
                secondLabel = getString(R.string.autofill_username)
            )
            android.widget.Toast.makeText(this, R.string.password_copied, android.widget.Toast.LENGTH_SHORT).show()
            result
        }
        
        if (queued) {
            // Close the picker only when queued action is available.
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            android.widget.Toast.makeText(
                this,
                R.string.smart_copy_notification_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
        

    
    private fun prewarmPasswordAutofill(password: PasswordEntry) {
        val currentPreparation = passwordAutofillPreparation
        if (
            passwordAutofillPreparationKey == password.id &&
            currentPreparation != null &&
            !currentPreparation.isCancelled
        ) {
            return
        }
        passwordAutofillPreparationKey = password.id
        passwordAutofillPreparation = lifecycleScope.async {
            preparePasswordAutofill(password)
        }
    }

    private suspend fun awaitPasswordAutofillPreparation(
        password: PasswordEntry
    ): PasswordAutofillPreparation {
        val currentPreparation = passwordAutofillPreparation
        if (passwordAutofillPreparationKey == password.id && currentPreparation != null) {
            return runCatching { currentPreparation.await() }
                .getOrElse { preparePasswordAutofill(password) }
        }
        return preparePasswordAutofill(password)
    }

    private fun handleAutofill(password: PasswordEntry, forceAddUri: Boolean) {
        if (!passwordAutofillInFlight.compareAndSet(false, true)) {
            AutofillLogger.d("PICKER", "Ignore duplicate password autofill tap while result is in flight")
            return
        }

        lifecycleScope.launch {
            try {
                when (val preparation = awaitPasswordAutofillPreparation(password)) {
                    is PasswordAutofillPreparation.Cancel -> {
                        android.util.Log.w("AutofillPickerV2", preparation.logMessage)
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                    is PasswordAutofillPreparation.Manual -> {
                        if (scheduleManualAccessibilityFill(preparation.accountValue, preparation.passwordValue)) {
                            finish()
                            return@launch
                        }
                        copyManualCredentialFallback(preparation.accountValue, preparation.passwordValue)
                    }
                    is PasswordAutofillPreparation.Fill -> {
                        val autofillIds = args.autofillIds.orEmpty()
                        val hints = args.autofillHints
                        AutofillLogger.i(
                            "PICKER",
                            "Auth strict mapping diagnostics",
                            metadata = mapOf(
                                "idCount" to autofillIds.size,
                                "hintCount" to (hints?.size ?: 0),
                                "strictFilledCount" to preparation.strictFilledCount,
                                "hasOtpHint" to preparation.hasOtpHint,
                                "otpResolved" to preparation.otpResolved,
                                "allowAccountInEmailField" to preparation.allowAccountInEmailField,
                                "unmatchedHintPreview" to if (preparation.unmatchedHintPreview.isEmpty()) {
                                    "none"
                                } else {
                                    preparation.unmatchedHintPreview.joinToString(",")
                                },
                            )
                        )

                        if (preparation.usedFallback) {
                            AutofillLogger.i(
                                "PICKER",
                                "Auth fallback mapping diagnostics",
                                metadata = mapOf(
                                    "idCount" to autofillIds.size,
                                    "hintCount" to (hints?.size ?: 0),
                                    "fallbackFilledCount" to preparation.fallbackFilledCount,
                                    "strictFilledCount" to preparation.strictFilledCount,
                                )
                            )
                        }

                        android.util.Log.i(
                            "AutofillPickerV2",
                            "Autofill auth result prepared: filledCount=${preparation.filledCount}, ids=${autofillIds.size}, " +
                                "hints=${hints?.joinToString(",") ?: "none"}, passwordId=${password.id}"
                        )
                        AutofillLogger.i(
                            "PICKER",
                            "Auth result prepared: filled=${preparation.filledCount}, ids=${autofillIds.size}, passwordId=${password.id}, hints=${hints?.joinToString(",") ?: "none"}"
                        )

                        val dataset = buildResultDataset(
                            title = password.title.ifBlank { getString(R.string.autofill_manual_entry_title) },
                            subtitle = preparation.accountValue.ifBlank {
                                args.webDomain
                                    ?: args.applicationId
                                    ?: getString(R.string.app_name)
                            },
                            filledValues = preparation.filledValues,
                        )
                        val authResult: Parcelable = if (args.responseAuthMode) {
                            FillResponse.Builder().addDataset(dataset).build()
                        } else {
                            dataset
                        }
                        val resultIntent = Intent().apply {
                            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
                        }

                        android.util.Log.i(
                            "AutofillPickerV2",
                            "Returning authentication result to framework: mode=${if (args.responseAuthMode) "fill_response" else "dataset"}"
                        )
                        AutofillLogger.i(
                            "PICKER",
                            "Returning auth result: mode=${if (args.responseAuthMode) "fill_response" else "dataset"}, passwordId=${password.id}"
                        )
                        setResult(Activity.RESULT_OK, resultIntent)
                        launchPasswordAutofillSideEffects(password, forceAddUri)
                        finish()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AutofillPickerV2", "Password autofill failed", e)
                AutofillLogger.e("PICKER", "Password autofill failed", e)
                setResult(Activity.RESULT_CANCELED)
                finish()
            } finally {
                if (!isFinishing) {
                    passwordAutofillInFlight.set(false)
                }
            }
        }
    }

    private suspend fun preparePasswordAutofill(
        password: PasswordEntry
    ): PasswordAutofillPreparation = withContext(Dispatchers.Default) {
        val securityManager = SecurityManager(applicationContext)
        val accountValue = AccountFillPolicy.resolveAccountIdentifier(password, securityManager)
        val fillEmailWithAccount = AccountFillPolicy.shouldFillEmailWithAccount(applicationContext)
        val decryptedPassword = AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = password.password,
            logTag = "AutofillPickerV2",
        )
        val hints = args.autofillHints
        val hasPasswordTarget = hints?.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name ||
                it == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name
        } == true
        if ((isManualMode || hasPasswordTarget) && decryptedPassword.isNullOrBlank()) {
            return@withContext PasswordAutofillPreparation.Cancel(
                logMessage = "Autofill canceled: password decryption unavailable"
            )
        }

        if (isManualMode) {
            return@withContext PasswordAutofillPreparation.Manual(
                accountValue = accountValue,
                passwordValue = decryptedPassword.orEmpty()
            )
        }

        val autofillIds = args.autofillIds
        if (autofillIds.isNullOrEmpty()) {
            return@withContext PasswordAutofillPreparation.Cancel(
                logMessage = "Autofill canceled: no AutofillId target"
            )
        }

        val filledValues = linkedMapOf<AutofillId, String>()
        val normalizedHints = hints.orEmpty().map { it.trim().lowercase() }
        val hasOtpHint = normalizedHints.any { isOtpHint(it) }
        val selectedOtpCode = if (hasOtpHint) {
            generateOtpCodeForPassword(password)
        } else {
            null
        }
        val hasUsernameHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name.lowercase() || it.contains("username")
        }
        val hasPhoneHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name.lowercase() ||
                it.contains("phone") ||
                it.contains("mobile") ||
                it.contains("tel")
        }
        val hasEmailHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name.lowercase() || it.contains("email")
        }
        val hasAccountHint = hasUsernameHint || hasPhoneHint
        val allowAccountInEmailField =
            fillEmailWithAccount || accountValue.contains("@") || (!hasAccountHint && hasEmailHint)
        var filledCount = 0
        var strictFilledCount = 0
        var fallbackFilledCount = 0
        val unmatchedHintPreview = mutableListOf<String>()
        autofillIds.forEachIndexed { index, autofillId ->
            val normalizedHint = hints?.getOrNull(index)?.trim()?.lowercase().orEmpty()
            val value = when {
                isOtpHint(normalizedHint) -> selectedOtpCode
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name.lowercase() ||
                    normalizedHint.contains("username") -> accountValue
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name.lowercase() ||
                    normalizedHint.contains("phone") ||
                    normalizedHint.contains("mobile") ||
                    normalizedHint.contains("tel") -> accountValue
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name.lowercase() ||
                    normalizedHint.contains("email") -> if (allowAccountInEmailField) accountValue else null
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name.lowercase() ||
                    normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name.lowercase() ||
                    normalizedHint.contains("password") ||
                    normalizedHint.contains("pass") -> decryptedPassword
                else -> null
            }
            if (value != null) {
                filledValues[autofillId] = value
                filledCount++
                strictFilledCount++
            } else if (unmatchedHintPreview.size < 6) {
                unmatchedHintPreview += if (normalizedHint.isBlank()) "(blank)" else normalizedHint
            }
        }

        var usedFallback = false
        if (filledCount == 0) {
            usedFallback = true
            android.util.Log.w("AutofillPickerV2", "No strict hint matched, trying controlled fallback")
            autofillIds.forEachIndexed { index, autofillId ->
                val normalizedHint = hints?.getOrNull(index)?.lowercase().orEmpty()
                val fallbackValue = when {
                    isOtpHint(normalizedHint) -> selectedOtpCode
                    normalizedHint.contains("pass") -> decryptedPassword
                    normalizedHint.contains("user") ||
                        normalizedHint.contains("email") ||
                        normalizedHint.contains("phone") ||
                        normalizedHint.contains("mobile") ||
                        normalizedHint.contains("tel") ||
                        normalizedHint.contains("号码") ||
                        normalizedHint.contains("手机号") ||
                        normalizedHint.contains("account") ||
                        normalizedHint.contains("login") -> accountValue
                    autofillIds.size == 1 -> if (accountValue.isNotBlank()) accountValue else decryptedPassword
                    index == 0 -> accountValue
                    index == 1 -> decryptedPassword
                    else -> null
                }
                if (!fallbackValue.isNullOrBlank()) {
                    filledValues[autofillId] = fallbackValue
                    filledCount++
                    fallbackFilledCount++
                }
            }
        }

        if (filledCount == 0) {
            return@withContext PasswordAutofillPreparation.Cancel(
                logMessage = "No credential value resolved after controlled fallback"
            )
        }

        PasswordAutofillPreparation.Fill(
            accountValue = accountValue,
            filledValues = filledValues,
            filledCount = filledCount,
            strictFilledCount = strictFilledCount,
            fallbackFilledCount = fallbackFilledCount,
            hasOtpHint = hasOtpHint,
            otpResolved = !selectedOtpCode.isNullOrBlank(),
            allowAccountInEmailField = allowAccountInEmailField,
            unmatchedHintPreview = unmatchedHintPreview,
            usedFallback = usedFallback
        )
    }

    private fun handleGeneratedPasswordFill(generatedPassword: String) {
        val password = generatedPassword.trim()
        if (password.isBlank()) return

        if (isManualMode) {
            if (scheduleManualAccessibilityFill("", password, preferPasswordField = true)) {
                finish()
                return
            }
            copyToClipboard(getString(R.string.autofill_password), password, true)
            finish()
            return
        }

        val autofillIds = args.autofillIds
        if (autofillIds.isNullOrEmpty()) {
            if (scheduleManualAccessibilityFill("", password, preferPasswordField = true)) {
                finish()
            } else {
                copyToClipboard(getString(R.string.autofill_password), password, true)
                finish()
            }
            return
        }

        val filledValues = buildGeneratedPasswordFillValues(
            autofillIds = autofillIds,
            hints = args.autofillHints,
            password = password
        )
        if (filledValues.isEmpty()) {
            copyToClipboard(getString(R.string.autofill_password), password, true)
            android.widget.Toast.makeText(
                this,
                R.string.password_copied,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dataset = buildResultDataset(
            title = getString(R.string.autofill_generated_password_title),
            subtitle = args.webDomain
                ?: args.applicationId
                ?: getString(R.string.app_name),
            filledValues = filledValues
        )
        val authResult: Parcelable = if (args.responseAuthMode) {
            FillResponse.Builder().addDataset(dataset).build()
        } else {
            dataset
        }
        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
        }
        AutofillLogger.i(
            "PICKER",
            "Returning generated password autofill: filled=${filledValues.size}, ids=${autofillIds.size}"
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun buildGeneratedPasswordFillValues(
        autofillIds: List<AutofillId>,
        hints: List<String>?,
        password: String,
    ): Map<AutofillId, String> {
        val filledValues = linkedMapOf<AutofillId, String>()
        autofillIds.forEachIndexed { index, autofillId ->
            val normalizedHint = hints?.getOrNull(index)?.trim()?.lowercase().orEmpty()
            val isPasswordTarget =
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name.lowercase() ||
                    normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name.lowercase() ||
                    normalizedHint.contains("password") ||
                    normalizedHint.contains("pass")
            if (isPasswordTarget) {
                filledValues[autofillId] = password
            }
        }
        if (filledValues.isEmpty() && autofillIds.size == 1) {
            filledValues[autofillIds.first()] = password
        }
        return filledValues
    }

    private fun scheduleManualAccessibilityFill(
        username: String,
        password: String,
        preferPasswordField: Boolean = false,
        expectedTargetPackage: String? = manualTargetPackage,
    ): Boolean {
        if (!MonicaAccessibilityService.isCredentialFillAvailable(applicationContext)) {
            AutofillLogger.i("PICKER", "Manual accessibility fill unavailable; service is not active")
            return false
        }

        val appContext = applicationContext
        val packageNameToSkip = packageName
        Handler(Looper.getMainLooper()).postDelayed({
            requestManualAccessibilityFillWithRetry(
                appContext = appContext,
                packageNameToSkip = packageNameToSkip,
                expectedTargetPackage = expectedTargetPackage,
                username = username,
                password = password,
                preferPasswordField = preferPasswordField,
                attempt = 1,
            )
        }, MANUAL_ACCESSIBILITY_FILL_DELAY_MS)

        setResult(Activity.RESULT_CANCELED)
        moveTaskToBack(true)
        AutofillLogger.i("PICKER", "Manual accessibility fill scheduled")
        return true
    }

    private fun requestManualAccessibilityFillWithRetry(
        appContext: Context,
        packageNameToSkip: String,
        expectedTargetPackage: String?,
        username: String,
        password: String,
        preferPasswordField: Boolean,
        attempt: Int,
    ) {
        val activePackage = MonicaAccessibilityService.getActiveWindowPackageName().orEmpty()
        val resolvedTargetPackage = resolveManualFillTargetPackage(
            activePackage = activePackage,
            packageNameToSkip = packageNameToSkip,
            expectedTargetPackage = expectedTargetPackage,
        )
        val filled = if (resolvedTargetPackage == null) {
            false
        } else {
            MonicaAccessibilityService.requestCredentialFill(
                targetPackageName = resolvedTargetPackage,
                username = username,
                password = password,
                preferPasswordField = preferPasswordField,
            )
        }

        AutofillLogger.i(
            "PICKER",
            "Manual accessibility fill attempt: attempt=$attempt, filled=$filled, " +
                "activePackage=${activePackage.ifBlank { "none" }}, " +
                "expectedPackage=${expectedTargetPackage.orEmpty().ifBlank { "any" }}",
        )

        if (filled || attempt >= MANUAL_ACCESSIBILITY_MAX_ATTEMPTS) {
            if (!filled) {
                if (!expectedTargetPackage.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        appContext,
                        R.string.autofill_active_fill_target_unavailable,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (preferPasswordField && username.isBlank()) {
                    ClipboardUtils.copyToClipboard(
                        context = appContext,
                        text = password,
                        label = appContext.getString(R.string.autofill_password),
                        sensitive = true,
                    )
                    android.widget.Toast.makeText(
                        appContext,
                        R.string.password_copied,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    copyManualCredentialFallback(
                        context = appContext,
                        username = username,
                        password = password,
                        usernameLabel = appContext.getString(R.string.autofill_username),
                        passwordLabel = appContext.getString(R.string.autofill_password),
                    )
                }
            }
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            requestManualAccessibilityFillWithRetry(
                appContext = appContext,
                packageNameToSkip = packageNameToSkip,
                expectedTargetPackage = expectedTargetPackage,
                username = username,
                password = password,
                preferPasswordField = preferPasswordField,
                attempt = attempt + 1,
            )
        }, MANUAL_ACCESSIBILITY_RETRY_DELAY_MS)
    }

    private fun copyManualCredentialFallback(username: String, password: String) {
        copyManualCredentialFallback(
            context = this,
            username = username,
            password = password,
            usernameLabel = getString(R.string.autofill_username),
            passwordLabel = getString(R.string.autofill_password)
        )
        finish()
    }

    private fun copyManualCredentialFallback(
        context: Context,
        username: String,
        password: String,
        usernameLabel: String,
        passwordLabel: String,
    ) {
        val queued = SmartCopyNotificationHelper.copyAndQueueNext(
            context = context,
            firstValue = password,
            firstLabel = passwordLabel,
            secondValue = username,
            secondLabel = usernameLabel
        )
        android.widget.Toast.makeText(context, R.string.password_copied, android.widget.Toast.LENGTH_SHORT).show()
        if (!queued) {
            android.widget.Toast.makeText(
                context,
                R.string.smart_copy_notification_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildResultDataset(
        title: String,
        subtitle: String,
        filledValues: Map<AutofillId, String>,
    ): Dataset {
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = this,
            title = title.ifBlank { getString(R.string.autofill_manual_entry_title) },
            username = subtitle.ifBlank { getString(R.string.app_name) }
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        filledValues.forEach { (autofillId, value) ->
            if (value.isNotBlank()) {
                fields[autofillId] = AutofillDatasetBuilder.FieldData(
                    value = AutofillValue.forText(value),
                    presentation = menuPresentation
                )
            }
        }
        return AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) { null }.build()
    }

    private fun handleBankCardAutofill(item: SecureItem) {
        val (_, data) = parseBankCardCandidate(
            item,
            decryptIfNeeded = SecurityManager(applicationContext)::decryptDataIfMonicaCiphertext
        ) ?: run {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (isManualMode) {
            val manualValue = data.cardNumber.ifBlank { data.cardholderName }
            if (manualValue.isNotBlank()) {
                copyToClipboard(getString(R.string.item_type_bank_card), manualValue, true)
            }
            finish()
            return
        }

        val autofillIds = args.autofillIds
        if (autofillIds.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val filledValues = linkedMapOf<AutofillId, String>()
        val hints = args.autofillHints
        var filledCount = 0
        autofillIds.forEachIndexed { index, autofillId ->
            val value = mapBankCardAutofillValue(hints?.getOrNull(index), data)
            if (!value.isNullOrBlank()) {
                filledValues[autofillId] = value
                filledCount++
            }
        }

        if (filledCount == 0 && autofillIds.size == 1) {
            val fallbackValue = data.cardNumber.ifBlank { data.cardholderName }
            if (fallbackValue.isNotBlank()) {
                filledValues[autofillIds.first()] = fallbackValue
                filledCount = 1
            }
        }

        if (filledCount == 0) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val dataset = buildResultDataset(
            title = bankCardDisplayTitle(item, data),
            subtitle = bankCardDisplaySubtitle(data),
            filledValues = filledValues,
        )
        val authResult: Parcelable = if (args.responseAuthMode) {
            FillResponse.Builder().addDataset(dataset).build()
        } else {
            dataset
        }
        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
        }
        AutofillLogger.i(
            "PICKER",
            "Returning bank card autofill: itemId=${item.id}, filled=$filledCount, hints=${hints?.joinToString(",") ?: "none"}"
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun handleDocumentAutofill(item: SecureItem) {
        val (_, data) = parseDocumentCandidate(
            item,
            decryptIfNeeded = SecurityManager(applicationContext)::decryptDataIfMonicaCiphertext
        ) ?: run {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (isManualMode) {
            val displayName = listOf(data.firstName, data.middleName, data.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { data.fullName }
            val manualValue = data.documentNumber.ifBlank { displayName }
            if (manualValue.isNotBlank()) {
                copyToClipboard(getString(R.string.item_type_document), manualValue, true)
            }
            finish()
            return
        }

        val autofillIds = args.autofillIds
        if (autofillIds.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val filledValues = linkedMapOf<AutofillId, String>()
        val hints = args.autofillHints
        var filledCount = 0
        autofillIds.forEachIndexed { index, autofillId ->
            val value = mapDocumentAutofillValue(hints?.getOrNull(index), data)
            if (!value.isNullOrBlank()) {
                filledValues[autofillId] = value
                filledCount++
            }
        }

        if (filledCount == 0 && autofillIds.size == 1) {
            val displayName = listOf(data.firstName, data.middleName, data.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { data.fullName }
            val fallbackValue = data.documentNumber
                .ifBlank { displayName }
            if (fallbackValue.isNotBlank()) {
                filledValues[autofillIds.first()] = fallbackValue
                filledCount = 1
            }
        }

        if (filledCount == 0) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val dataset = buildResultDataset(
            title = documentDisplayTitle(item, data),
            subtitle = documentDisplaySubtitle(data),
            filledValues = filledValues,
        )
        val authResult: Parcelable = if (args.responseAuthMode) {
            FillResponse.Builder().addDataset(dataset).build()
        } else {
            dataset
        }
        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
        }
        AutofillLogger.i(
            "PICKER",
            "Returning document autofill: itemId=${item.id}, filled=$filledCount, hints=${hints?.joinToString(",") ?: "none"}"
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun handleBillingAddressAutofill(item: SecureItem) {
        val (_, data) = parseBillingAddressCandidate(
            item,
            decryptIfNeeded = SecurityManager(applicationContext)::decryptDataIfMonicaCiphertext
        ) ?: run {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (isManualMode) {
            val manualValue = data.formatForDisplay()
            if (manualValue.isNotBlank()) {
                copyToClipboard(getString(R.string.billing_address), manualValue, false)
            }
            finish()
            return
        }

        val autofillIds = args.autofillIds
        if (autofillIds.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val filledValues = linkedMapOf<AutofillId, String>()
        val hints = args.autofillHints
        var filledCount = 0
        autofillIds.forEachIndexed { index, autofillId ->
            val value = mapBillingAddressAutofillValue(hints?.getOrNull(index), data)
            if (!value.isNullOrBlank()) {
                filledValues[autofillId] = value
                filledCount++
            }
        }

        if (filledCount == 0 && autofillIds.size == 1) {
            val fallbackValue = data.toAutofillAddressForPicker().ifBlank { data.fullName }
            if (fallbackValue.isNotBlank()) {
                filledValues[autofillIds.first()] = fallbackValue
                filledCount = 1
            }
        }

        if (filledCount == 0) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val dataset = buildResultDataset(
            title = billingAddressDisplayTitle(item, data),
            subtitle = billingAddressDisplaySubtitle(data),
            filledValues = filledValues,
        )
        val authResult: Parcelable = if (args.responseAuthMode) {
            FillResponse.Builder().addDataset(dataset).build()
        } else {
            dataset
        }
        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
        }
        AutofillLogger.i(
            "PICKER",
            "Returning billing address autofill: itemId=${item.id}, filled=$filledCount, hints=${hints?.joinToString(",") ?: "none"}"
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun isOtpHint(normalizedHint: String): Boolean {
        if (normalizedHint.isBlank()) return false
        return normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.OTP_CODE.name.lowercase() ||
            normalizedHint.contains("totp") ||
            normalizedHint.contains("otp") ||
            normalizedHint.contains("2fa") ||
            normalizedHint.contains("twofactor") ||
            normalizedHint.contains("two_factor") ||
            normalizedHint.contains("verification") ||
            normalizedHint.contains("验证码") ||
            normalizedHint.contains("驗證碼") ||
            normalizedHint.contains("一次性")
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchPasswordAutofillSideEffects(password: PasswordEntry, forceAddUri: Boolean) {
        if (forceAddUri) {
            saveUriBinding(password)
        }

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            if (args.rememberLastFilled) {
                rememberLastFilledCredential(password.id)
            }
            rememberLearnedFieldSignature()
            processSelectedOtpActions(password)
        }
    }

    private suspend fun processSelectedOtpActions(password: PasswordEntry) {
        val isOtpTarget = args.autofillHints
            ?.map { it.trim().lowercase() }
            ?.any(::isOtpHint) == true
        if (isOtpTarget) {
            AutofillLogger.d("OTP", "Skip OTP auto action for OTP-target fill request")
            return
        }

        runCatching {
            val preferences = AutofillPreferences(applicationContext)
            val showNotification = withContext(Dispatchers.IO) {
                preferences.isOtpNotificationEnabled.first()
            }
            val autoCopy = withContext(Dispatchers.IO) {
                preferences.isAutoCopyOtpEnabled.first()
            }
            if (!showNotification && !autoCopy) return

            val totpData = resolveOtpDataForPassword(password)
            if (totpData == null) {
                AutofillLogger.w(
                    "OTP",
                    "Skip OTP notify/copy: no authenticator key or bound validator entry found for passwordId=${password.id}"
                )
                return
            }
            AutofillLogger.i(
                "OTP",
                "Resolved OTP source: passwordId=${password.id}, otpType=${totpData.otpType}, secretLen=${totpData.secret.length}, boundPasswordId=${totpData.boundPasswordId}"
            )
            val resolvedTotpData = resolveTotpDataForGeneration(totpData)
            val code = TotpGenerator.generateOtp(resolvedTotpData)
            AutofillLogger.i(
                "OTP",
                "Selected OTP generated: passwordId=${password.id}, type=${resolvedTotpData.otpType}, codeLen=${code.length}"
            )
            if (autoCopy) {
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
                }
                AutofillLogger.d("OTP", "Auto-copied selected credential OTP")
            }
            if (showNotification) {
                val durationSeconds = withContext(Dispatchers.IO) {
                    preferences.otpNotificationDuration.first()
                }
                takagi.ru.monica.autofill_ng.service.AutofillOtpNotificationService.start(
                    context = applicationContext,
                    totpData = resolvedTotpData,
                    label = password.title,
                    durationSeconds = durationSeconds
                )
            }
        }.onFailure { e ->
            AutofillLogger.e("OTP", "Failed selected OTP action", e)
        }
    }

    private suspend fun generateOtpCodeForPassword(password: PasswordEntry): String? {
        val totpData = resolveOtpDataForPassword(password)
        if (totpData == null) {
            AutofillLogger.w(
                "OTP",
                "Skip OTP fill: no authenticator key or bound validator entry found for passwordId=${password.id}"
            )
            return null
        }
        return runCatching {
            val resolvedTotpData = resolveTotpDataForGeneration(totpData)
            val code = TotpGenerator.generateOtp(resolvedTotpData)
            AutofillLogger.i(
                "OTP",
                "Generated OTP for fill: passwordId=${password.id}, type=${resolvedTotpData.otpType}, codeLen=${code.length}"
            )
            code.takeIf { it.isNotBlank() }
        }.onFailure { e ->
            AutofillLogger.e("OTP", "Failed OTP fill generation", e)
        }.getOrNull()
    }

    private fun parsePasswordAuthenticatorTotpData(authenticatorKey: String): TotpData? {
        val securityManager = SecurityManager(applicationContext)
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = runCatching {
                securityManager.decryptDataIfMonicaCiphertext(authenticatorKey)
            }.getOrDefault(authenticatorKey)
        )
    }

    private suspend fun resolveOtpDataForPassword(password: PasswordEntry): TotpData? {
        val passwordTotpData = password.authenticatorKey
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::parsePasswordAuthenticatorTotpData)
        return resolveOtpFromExistingValidators(password, passwordTotpData) ?: passwordTotpData
    }

    private suspend fun resolveOtpFromExistingValidators(
        password: PasswordEntry,
        passwordTotpData: TotpData?
    ): TotpData? {
        val validatorTotpList = withContext(Dispatchers.IO) {
            val securityManager = SecurityManager(applicationContext)
            val dao = PasswordDatabase.getDatabase(applicationContext).secureItemDao()
            dao.getActiveItemsByTypeSync(ItemType.TOTP)
                .mapNotNull { item ->
                    TotpDataResolver.parseStoredItemData(
                        itemData = item.itemData,
                        fallbackIssuer = item.title,
                        decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                    )
                }
        }

        if (validatorTotpList.isEmpty()) return null

        validatorTotpList.firstOrNull { it.boundPasswordId == password.id }?.let { return it }

        val identityKey = buildTotpIdentityKey(passwordTotpData)
        if (identityKey.isNotEmpty()) {
            validatorTotpList.firstOrNull { buildTotpIdentityKey(it) == identityKey }?.let { return it }
        }

        return null
    }

    private fun buildTotpIdentityKey(data: TotpData?): String {
        val normalized = data?.let { TotpDataResolver.normalizeTotpData(it) } ?: return ""
        val normalizedSecret = TotpDataResolver.normalizeBase32Secret(normalized.secret)
        return listOf(
            normalized.otpType.name,
            normalizedSecret,
            normalized.digits.toString(),
            normalized.period.toString(),
            normalized.algorithm.uppercase(),
            normalized.counter.toString()
        ).joinToString("|")
    }

    private fun resolveTotpDataForGeneration(totpData: TotpData): TotpData {
        val securityManager = SecurityManager(applicationContext)
        val decryptResult = runCatching { securityManager.decryptData(totpData.secret) }
        val decryptedSecret = decryptResult.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        AutofillLogger.i(
            "OTP",
            "OTP secret resolve: otpType=${totpData.otpType}, rawLen=${totpData.secret.length}, decryptSuccess=${decryptResult.isSuccess && !decryptedSecret.isNullOrEmpty()}, resolvedLen=${decryptedSecret?.length ?: totpData.secret.length}"
        )
        return if (!decryptedSecret.isNullOrEmpty()) {
            totpData.copy(secret = decryptedSecret)
        } else {
            totpData
        }
    }

    private suspend fun rememberLastFilledCredential(passwordId: Long) {
        val normalizedIdentifiers = buildList {
            args.interactionIdentifier
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            args.interactionIdentifierAliases
                ?.asSequence()
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotBlank() }
                ?.forEach(::add)
        }.distinct()
        val primaryIdentifier = normalizedIdentifiers.firstOrNull() ?: return
        try {
            android.util.Log.i(
                "AutofillPickerV2",
                "Persisting last-filled credential: passwordId=$passwordId, primaryId=$primaryIdentifier"
            )
            AutofillLogger.i(
                "PICKER",
                "Persisting last-filled credential: passwordId=$passwordId, primaryId=$primaryIdentifier"
            )
            withContext(Dispatchers.IO) {
                val preferences = AutofillPreferences(applicationContext)
                normalizedIdentifiers.forEach { identifier ->
                    preferences.completeAutofillInteraction(identifier, passwordId)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AutofillPickerV2", "Failed to persist last filled credential", e)
            AutofillLogger.e("PICKER", "Failed to persist last-filled credential", e)
        }
    }

    private suspend fun rememberLearnedFieldSignature() {
        val signatureKey = args.fieldSignatureKey?.trim()?.lowercase().orEmpty()
        if (signatureKey.isBlank()) return
        try {
            withContext(Dispatchers.IO) {
                AutofillPreferences(applicationContext).markFieldSignatureLearned(signatureKey)
            }
        } catch (e: Exception) {
            android.util.Log.e("AutofillPickerV2", "Failed to persist learned field signature", e)
        }
    }
    
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun saveUriBinding(password: PasswordEntry) {
        // TODO: 保存 URI 绑定到数据库
        val applicationId = args.applicationId
        val webDomain = args.webDomain
        
        android.util.Log.d(
            "AutofillPickerV2",
            "Saving URI binding: hasApp=${!applicationId.isNullOrBlank()}, hasWeb=${!webDomain.isNullOrBlank()}"
        )
        
        // 后台保存
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val database = PasswordDatabase.getDatabase(applicationContext)
                val repository = PasswordRepository(database.passwordEntryDao())
                
                val updatedAppBinding = applicationId?.let { packageName ->
                    addOrReplaceLinkedAppBinding(
                        password.appPackageName,
                        password.appName,
                        packageName,
                        packageName
                    )
                }

                val updatedEntry = password.copy(
                    appPackageName = updatedAppBinding?.first ?: password.appPackageName,
                    appName = updatedAppBinding?.second ?: password.appName,
                    website = if (!webDomain.isNullOrEmpty() && !password.website.contains(webDomain)) {
                        if (password.website.isNotEmpty()) "${password.website}, $webDomain"
                        else webDomain
                    } else password.website
                )
                
                repository.updatePasswordEntry(updatedEntry)
                android.util.Log.d("AutofillPickerV2", "URI binding saved successfully")
            } catch (e: Exception) {
                android.util.Log.e("AutofillPickerV2", "Failed to save URI binding", e)
            }
        }
    }


    /**
     * 复制内容到剪贴板
     */
    private fun copyToClipboard(label: String, text: String, isSensitive: Boolean = false) {
        try {
            ClipboardUtils.copyToClipboard(
                context = this,
                text = text,
                label = label,
                sensitive = isSensitive || ClipboardUtils.isCredentialLabel(label)
            )
            
            val messageRes = if (isSensitive) R.string.password_copied else R.string.username_copied
            android.widget.Toast.makeText(this, messageRes, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("AutofillPickerV2", "Clipboard copy failed", e)
        }
    }
}

private data class AutofillPickerLoadedData(
    val suggestedPasswords: List<PasswordEntry>,
    val allPasswords: List<PasswordEntry>,
    val allBankCards: List<SecureItem>,
    val allDocuments: List<SecureItem>,
    val allBillingAddresses: List<SecureItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutofillPickerContent(
    args: AutofillPickerActivityV2.Args,
    repository: PasswordRepository,
    securityManager: SecurityManager,
    keepassDatabases: List<LocalKeePassDatabase>,
    generatorPreferences: GeneratorPreferences? = null,
    canSkipVerification: Boolean = false,
    requireAuthentication: Boolean = true,
    biometricEnabled: Boolean = false,
    autoLockMinutes: Int = 5,
    iconCardsEnabled: Boolean = false,
    isManualMode: Boolean = false,
    showTargetContextInManualMode: Boolean = false,
    manualModeReason: String = "unknown",
    onPrepareAutofill: (PasswordEntry) -> Unit,
    onAutofill: (PasswordEntry, Boolean) -> Unit,
    onAutofillBankCard: (SecureItem) -> Unit,
    onAutofillDocument: (SecureItem) -> Unit,
    onAutofillBillingAddress: (SecureItem) -> Unit,
    onFillGeneratedPassword: (String) -> Unit,
    onCopy: (String, String, Boolean) -> Unit,
    onClose: () -> Unit,
    onMarkAsNonAutofill: () -> Unit,
    onSmartCopy: (PasswordEntry, Boolean) -> Unit
) {
    // 导航状态: "list", "detail", "add"
    var currentScreen by remember { mutableStateOf("list") }
    var selectedPassword by remember { mutableStateOf<PasswordEntry?>(null) }
    
    var allPasswords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
    var suggestedPasswords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
    var searchedPasswords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
    var allBankCards by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    var allDocuments by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    var allBillingAddresses by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchLoading by remember { mutableStateOf(false) }
    var showMarkAsNonAutofillDialog by remember { mutableStateOf(false) }
    var structuredCopyDialog by remember { mutableStateOf<StructuredAutofillCopyDialogState?>(null) }
    var showGeneratedPasswordSheet by rememberSaveable { mutableStateOf(false) }
    val defaultGeneratedPasswordOptions = remember { AutofillPasswordGeneratorOptions() }
    var generatedPasswordLength by rememberSaveable { mutableIntStateOf(defaultGeneratedPasswordOptions.length) }
    var generatedIncludeUppercase by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.includeUppercase) }
    var generatedIncludeLowercase by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.includeLowercase) }
    var generatedIncludeNumbers by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.includeNumbers) }
    var generatedIncludeSymbols by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.includeSymbols) }
    var generatedAllowedSymbols by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.allowedSymbols) }
    var generatedExcludeSimilar by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.excludeSimilar) }
    var generatedExcludeAmbiguous by rememberSaveable { mutableStateOf(defaultGeneratedPasswordOptions.excludeAmbiguous) }
    var generatedUppercaseMin by rememberSaveable { mutableIntStateOf(defaultGeneratedPasswordOptions.uppercaseMin) }
    var generatedLowercaseMin by rememberSaveable { mutableIntStateOf(defaultGeneratedPasswordOptions.lowercaseMin) }
    var generatedNumbersMin by rememberSaveable { mutableIntStateOf(defaultGeneratedPasswordOptions.numbersMin) }
    var generatedSymbolsMin by rememberSaveable { mutableIntStateOf(defaultGeneratedPasswordOptions.symbolsMin) }
    var generatorPreferencesApplied by rememberSaveable { mutableStateOf(false) }
    val generatedPasswordOptions = AutofillPasswordGeneratorOptions(
        length = generatedPasswordLength,
        includeUppercase = generatedIncludeUppercase,
        includeLowercase = generatedIncludeLowercase,
        includeNumbers = generatedIncludeNumbers,
        includeSymbols = generatedIncludeSymbols,
        allowedSymbols = generatedAllowedSymbols,
        excludeSimilar = generatedExcludeSimilar,
        excludeAmbiguous = generatedExcludeAmbiguous,
        uppercaseMin = generatedUppercaseMin,
        lowercaseMin = generatedLowercaseMin,
        numbersMin = generatedNumbersMin,
        symbolsMin = generatedSymbolsMin,
    )
    var generatedPassword by rememberSaveable { mutableStateOf(generateAutofillPassword(generatedPasswordOptions)) }
    LaunchedEffect(generatorPreferences) {
        val preferences = generatorPreferences ?: return@LaunchedEffect
        if (generatorPreferencesApplied) return@LaunchedEffect
        val options = preferences.toAutofillPasswordGeneratorOptions()
        generatedPasswordLength = options.length
        generatedIncludeUppercase = options.includeUppercase
        generatedIncludeLowercase = options.includeLowercase
        generatedIncludeNumbers = options.includeNumbers
        generatedIncludeSymbols = options.includeSymbols
        generatedAllowedSymbols = options.allowedSymbols
        generatedExcludeSimilar = options.excludeSimilar
        generatedExcludeAmbiguous = options.excludeAmbiguous
        generatedUppercaseMin = options.uppercaseMin
        generatedLowercaseMin = options.lowercaseMin
        generatedNumbersMin = options.numbersMin
        generatedSymbolsMin = options.symbolsMin
        generatedPassword = generateAutofillPassword(options)
        generatorPreferencesApplied = true
    }
    var useGeneratedPasswordForNextAdd by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf(AutofillStorageSourceFilter.ALL) }
    var selectedKeePassDatabaseId by remember { mutableStateOf<Long?>(null) }
    var selectedKeePassGroupPath by remember { mutableStateOf<String?>(null) }
    var selectedVaultId by remember { mutableStateOf<Long?>(null) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var isFilterExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    
    // 检查通知权限 - 用于决定是否显示智能复制选项
    val context = androidx.compose.ui.platform.LocalContext.current
    val autofillPreferences = remember(context) { AutofillPreferences(context) }
    val requestProfile = remember(args.autofillHints) {
        buildAutofillPickerRequestProfile(args.autofillHints)
    }
    val initialContentType = remember(requestProfile) {
        when {
            requestProfile.wantsBillingAddresses -> AutofillPickerContentType.BILLING_ADDRESS
            requestProfile.wantsBankCards -> AutofillPickerContentType.PAYMENT
            requestProfile.wantsDocuments -> AutofillPickerContentType.DOCUMENT
            else -> AutofillPickerContentType.ACCOUNT
        }
    }
    var selectedContentType by rememberSaveable { mutableStateOf(initialContentType) }
    val loginHintCount = remember(args.autofillHints) {
        args.autofillHints.orEmpty().count(::isLoginAutofillHint)
    }
    val bankCardHintCount = remember(args.autofillHints) {
        args.autofillHints.orEmpty().count(::isBankCardAutofillHint)
    }
    val documentHintCount = remember(args.autofillHints) {
        args.autofillHints.orEmpty().count(::isDocumentAutofillHint)
    }
    val billingAddressHintCount = remember(args.autofillHints) {
        args.autofillHints.orEmpty().count(::isBillingAddressKeyAutofillHint)
    }
    val addTargets = remember {
        listOf(
            AutofillAddTarget.PASSWORD,
            AutofillAddTarget.BANK_CARD,
            AutofillAddTarget.DOCUMENT,
            AutofillAddTarget.BILLING_ADDRESS,
        )
    }
    val defaultAddTarget = when (selectedContentType) {
        AutofillPickerContentType.ACCOUNT -> AutofillAddTarget.PASSWORD
        AutofillPickerContentType.PAYMENT -> AutofillAddTarget.BANK_CARD
        AutofillPickerContentType.DOCUMENT -> AutofillAddTarget.DOCUMENT
        AutofillPickerContentType.BILLING_ADDRESS -> AutofillAddTarget.BILLING_ADDRESS
    }
    var pendingAddTarget by rememberSaveable { mutableStateOf<AutofillAddTarget?>(null) }
    val appDb = remember(context) { PasswordDatabase.getDatabase(context.applicationContext) }
    val secureItemRepository = remember(appDb, securityManager) {
        SecureItemRepository(
            appDb.secureItemDao(),
            decryptSensitiveValue = securityManager::decryptDataIfMonicaCiphertext
        )
    }
    val customFieldRepository = remember(appDb) { CustomFieldRepository(appDb.customFieldDao()) }
    val hasNotificationPermission = remember {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val canMarkAsNonAutofill = !isManualMode && !args.isSaveMode && !args.fieldSignatureKey.isNullOrBlank()
    
    val autofillUsernameLabel = stringResource(R.string.autofill_username)
    val autofillPasswordLabel = stringResource(R.string.autofill_password)
    val autofillCopyPasswordUnavailable = stringResource(R.string.autofill_copy_password_unavailable)
    val autofillCopyAccountUnavailable = stringResource(R.string.autofill_copy_account_unavailable)
    val detectedAutofillTypeLabel = when {
        requestProfile.wantsBankCards && !requestProfile.wantsPasswords -> stringResource(R.string.item_type_bank_card)
        requestProfile.wantsDocuments && !requestProfile.wantsPasswords -> stringResource(R.string.item_type_document)
        requestProfile.wantsBillingAddresses && !requestProfile.wantsPasswords -> stringResource(R.string.billing_address)
        else -> stringResource(R.string.item_type_password)
    }
    val canDirectFillBankCard = !isManualMode &&
        !args.autofillIds.isNullOrEmpty() &&
        requestProfile.wantsBankCards
    val canDirectFillDocument = !isManualMode &&
        !args.autofillIds.isNullOrEmpty() &&
        requestProfile.wantsDocuments
    val canDirectFillBillingAddress = !isManualMode &&
        !args.autofillIds.isNullOrEmpty() &&
        requestProfile.wantsBillingAddresses

    val handlePasswordAction: (PasswordItemAction) -> Unit = { action ->
        when (action) {
            is PasswordItemAction.Autofill -> onAutofill(action.password, false)
            is PasswordItemAction.AutofillAndSaveUri -> onAutofill(action.password, true)
            is PasswordItemAction.ViewDetails -> {
                onPrepareAutofill(action.password)
                selectedPassword = action.password
                currentScreen = "detail"
            }
            is PasswordItemAction.CopyUsername -> {
                val accountValue = AccountFillPolicy.resolveAccountIdentifier(action.password, securityManager)
                if (accountValue.isNotBlank()) {
                    onCopy(autofillUsernameLabel, accountValue, false)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        autofillCopyAccountUnavailable,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            is PasswordItemAction.CopyPassword -> {
                val decryptedPassword = AutofillSecretResolver.decryptPasswordOrNull(
                    securityManager = securityManager,
                    encryptedOrPlain = action.password.password,
                    logTag = "AutofillPickerV2",
                )
                if (!decryptedPassword.isNullOrBlank()) {
                    onCopy(autofillPasswordLabel, decryptedPassword, true)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        autofillCopyPasswordUnavailable,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            is PasswordItemAction.SmartCopyUsernameFirst -> onSmartCopy(action.password, true)
            is PasswordItemAction.SmartCopyPasswordFirst -> onSmartCopy(action.password, false)
            is PasswordItemAction.FillUsername -> onAutofill(action.password, false)
            is PasswordItemAction.FillPassword -> onAutofill(action.password, false)
            is PasswordItemAction.FillTotp -> onAutofill(action.password, false)
        }
    }

    var isAuthenticated by remember(canSkipVerification, requireAuthentication) {
        mutableStateOf(canSkipVerification && !requireAuthentication)
    }

    if (!isAuthenticated) {
        Surface(modifier = Modifier.fillMaxSize()) {
            PasswordVerificationContent(
                modifier = Modifier.fillMaxSize(),
                isFirstTime = false,
                disablePasswordVerification = false,
                biometricEnabled = biometricEnabled,
                autoLockMinutes = autoLockMinutes,
                persistVaultUnlockToSession = false,
                onVerifyPassword = { input -> securityManager.unlockVaultWithPassword(input) },
                onSuccess = {
                    isAuthenticated = true
                }
            )
        }
        return
    }

    LaunchedEffect(Unit) {
        runCatching {
            val savedSource = autofillPreferences.v2DefaultSourceFilter.first()
            sourceFilter = savedSource.toUiFilter()
            selectedKeePassDatabaseId = autofillPreferences.v2DefaultKeepassDatabaseId.first()
            selectedVaultId = autofillPreferences.v2DefaultBitwardenVaultId.first()
        }.onFailure {
            android.util.Log.w("AutofillPickerV2", "Failed to restore picker defaults: ${it.message}")
        }
    }

    fun persistPickerDefaults() {
        coroutineScope.launch {
            runCatching {
                autofillPreferences.setV2DefaultSourceFilter(sourceFilter.toPreferenceFilter())
                autofillPreferences.setV2DefaultKeepassDatabaseId(selectedKeePassDatabaseId)
                autofillPreferences.setV2DefaultBitwardenVaultId(selectedVaultId)
            }.onFailure {
                android.util.Log.w("AutofillPickerV2", "Failed to persist picker defaults: ${it.message}")
            }
        }
    }
    
    val (appIcon, appName) = remember(args.applicationId) {
        args.applicationId?.let { packageName ->
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val icon = pm.getApplicationIcon(appInfo)
                val name = pm.getApplicationLabel(appInfo).toString()
                icon to name
            } catch (e: Exception) {
                null to null
            }
        } ?: (null to null)
    }
    val markNonAutofillTargetLabel = remember(args.webDomain, appName, args.applicationId) {
        args.webDomain?.takeIf { it.isNotBlank() }
            ?: appName?.takeIf { it.isNotBlank() }
            ?: args.applicationId?.takeIf { it.isNotBlank() }
            ?: ""
    }
    
    val bitwardenVaults by appDb.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val selectedVaultFolders by remember(appDb, selectedVaultId) {
        if (selectedVaultId != null) {
            appDb.bitwardenFolderDao().getFoldersByVaultFlow(selectedVaultId!!)
        } else {
            flowOf(emptyList<BitwardenFolder>())
        }
    }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        AutofillLogger.i(
            "PICKER_UI",
            "Picker content mounted",
            metadata = mapOf(
                "manualMode" to isManualMode,
                "manualReason" to manualModeReason,
                "mainAppAuthenticated" to canSkipVerification,
                "autofillAuthRequired" to requireAuthentication,
                "iconCardsEnabled" to iconCardsEnabled,
                "responseAuth" to args.responseAuthMode,
                "idCount" to (args.autofillIds?.size ?: 0),
                "hintCount" to (args.autofillHints?.size ?: 0),
                "loginHintCount" to loginHintCount,
                "bankCardHintCount" to bankCardHintCount,
                "documentHintCount" to documentHintCount,
                "wantsPasswords" to requestProfile.wantsPasswords,
                "wantsBankCards" to requestProfile.wantsBankCards,
                "wantsDocuments" to requestProfile.wantsDocuments,
            )
        )
    }

    LaunchedEffect(
        isManualMode,
        canMarkAsNonAutofill,
        args.isSaveMode,
        args.fieldSignatureKey,
        args.applicationId,
        args.webDomain,
        appName,
        markNonAutofillTargetLabel,
    ) {
        AutofillLogger.i(
            "PICKER_UI",
            "Picker source diagnostics",
            metadata = mapOf(
                "manualMode" to isManualMode,
                "manualReason" to manualModeReason,
                "canMarkAsNonAutofill" to canMarkAsNonAutofill,
                "isSaveMode" to args.isSaveMode,
                "fieldSignaturePresent" to !args.fieldSignatureKey.isNullOrBlank(),
                "applicationId" to (args.applicationId ?: "none"),
                "webDomain" to (args.webDomain ?: "none"),
                "resolvedAppName" to (appName ?: "none"),
                "markTargetLabel" to if (markNonAutofillTargetLabel.isBlank()) "none" else markNonAutofillTargetLabel,
                "autofillIdCount" to (args.autofillIds?.size ?: 0),
            )
        )
    }

    // 加载密码
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        try {
            val suggestedIds = args.suggestedPasswordIds?.toList() ?: emptyList()
            val loadedData = withContext(Dispatchers.IO) {
                AutofillPickerLoadedData(
                    suggestedPasswords = if (suggestedIds.isNotEmpty()) {
                        repository.getPasswordsByIds(suggestedIds)
                    } else {
                        emptyList()
                    },
                    allPasswords = repository.getAllPasswordEntries().first(),
                    allBankCards = secureItemRepository.getActiveItemsByType(ItemType.BANK_CARD).first(),
                    allDocuments = secureItemRepository.getActiveItemsByType(ItemType.DOCUMENT).first(),
                    allBillingAddresses = secureItemRepository.getActiveItemsByType(ItemType.BILLING_ADDRESS).first()
                )
            }
            suggestedPasswords = loadedData.suggestedPasswords
            allPasswords = loadedData.allPasswords
            allBankCards = loadedData.allBankCards
            allDocuments = loadedData.allDocuments
            allBillingAddresses = loadedData.allBillingAddresses
            AutofillLogger.i(
                "PICKER_UI",
                "Picker data load complete",
                metadata = mapOf(
                    "suggestedRequested" to suggestedIds.size,
                    "suggestedLoaded" to suggestedPasswords.size,
                    "allLoaded" to allPasswords.size,
                    "bankCardsLoaded" to allBankCards.size,
                    "documentsLoaded" to allDocuments.size,
                    "billingAddressesLoaded" to allBillingAddresses.size,
                    "elapsedMs" to (System.currentTimeMillis() - start)
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("AutofillPickerV2", "Error loading passwords", e)
            AutofillLogger.e(
                "PICKER_UI",
                "Picker data load failed",
                e,
                metadata = mapOf("elapsedMs" to (System.currentTimeMillis() - start))
            )
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            searchedPasswords = emptyList()
            isSearchLoading = false
            return@LaunchedEffect
        }
        searchedPasswords = emptyList()
        isSearchLoading = true
        try {
            // 防抖，避免每次按键都触发数据库搜索。
            delay(260)
            searchedPasswords = withContext(Dispatchers.IO) {
                repository.searchPasswordEntries(query).first()
            }
        } catch (e: Exception) {
            android.util.Log.e("AutofillPickerV2", "Error searching passwords", e)
            searchedPasswords = emptyList()
        } finally {
            isSearchLoading = false
        }
    }

    val keepassNameById = remember(keepassDatabases) {
        keepassDatabases.associate { it.id to it.name }
    }
    val vaultLabelById = remember(bitwardenVaults) {
        bitwardenVaults.associate { vault ->
            vault.id to (vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email)
        }
    }
    val folderNameById = remember(selectedVaultFolders) {
        selectedVaultFolders.associate { it.bitwardenFolderId to it.name }
    }
    val keepassGroupsForSelectedDb = remember(allPasswords, allBankCards, allDocuments, allBillingAddresses, selectedKeePassDatabaseId) {
        sequenceOf(
            allPasswords.asSequence().map { Triple(it.keepassDatabaseId, it.keepassGroupPath, Unit) },
            allBankCards.asSequence().map { Triple(it.keepassDatabaseId, it.keepassGroupPath, Unit) },
            allDocuments.asSequence().map { Triple(it.keepassDatabaseId, it.keepassGroupPath, Unit) },
            allBillingAddresses.asSequence().map { Triple(it.keepassDatabaseId, it.keepassGroupPath, Unit) }
        )
            .flatten()
            .filter { it.first == selectedKeePassDatabaseId }
            .mapNotNull { it.second?.trim()?.takeIf { path -> path.isNotBlank() } }
            .distinct()
            .sorted()
            .toList()
    }
    val hasUncategorizedKeepassEntries = remember(allPasswords, allBankCards, allDocuments, allBillingAddresses, selectedKeePassDatabaseId) {
        allPasswords.any { entry ->
            entry.keepassDatabaseId == selectedKeePassDatabaseId && entry.keepassGroupPath.isNullOrBlank()
        } || allBankCards.any { entry ->
            entry.keepassDatabaseId == selectedKeePassDatabaseId && entry.keepassGroupPath.isNullOrBlank()
        } || allDocuments.any { entry ->
            entry.keepassDatabaseId == selectedKeePassDatabaseId && entry.keepassGroupPath.isNullOrBlank()
        } || allBillingAddresses.any { entry ->
            entry.keepassDatabaseId == selectedKeePassDatabaseId && entry.keepassGroupPath.isNullOrBlank()
        }
    }
    val hasUncategorizedBitwardenEntries = remember(allPasswords, allBankCards, allDocuments, allBillingAddresses, selectedVaultId) {
        selectedVaultId != null && (
            allPasswords.any { entry ->
                entry.bitwardenVaultId == selectedVaultId && entry.bitwardenFolderId.isNullOrBlank()
            } || allBankCards.any { entry ->
                entry.bitwardenVaultId == selectedVaultId && entry.bitwardenFolderId.isNullOrBlank()
            } || allDocuments.any { entry ->
                entry.bitwardenVaultId == selectedVaultId && entry.bitwardenFolderId.isNullOrBlank()
            } || allBillingAddresses.any { entry ->
                entry.bitwardenVaultId == selectedVaultId && entry.bitwardenFolderId.isNullOrBlank()
            }
        )
    }

    val parsedBankCards = remember(allBankCards, securityManager) {
        allBankCards.mapNotNull { item ->
            parseBankCardCandidate(
                item,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            )
        }
    }
    val parsedDocuments = remember(allDocuments, securityManager) {
        allDocuments.mapNotNull { item ->
            parseDocumentCandidate(
                item,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            )
        }
    }
    val parsedBillingAddresses = remember(allBillingAddresses, securityManager) {
        allBillingAddresses.mapNotNull { item ->
            parseBillingAddressCandidate(
                item,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            )
        }
    }

    val basePasswords = if (searchQuery.isBlank()) allPasswords else searchedPasswords

    val sourceFilteredPasswords by remember(
        basePasswords,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedKeePassGroupPath,
        selectedVaultId,
        selectedFolderId
    ) {
        derivedStateOf {
            basePasswords.filter { entry ->
                when (sourceFilter) {
                    AutofillStorageSourceFilter.ALL -> true
                    AutofillStorageSourceFilter.LOCAL ->
                        entry.isLocalOnlyEntry()
                    AutofillStorageSourceFilter.KEEPASS -> {
                        val keepassId = entry.keepassDatabaseId
                        keepassId != null &&
                            (selectedKeePassDatabaseId == null || keepassId == selectedKeePassDatabaseId) &&
                            when (selectedKeePassGroupPath) {
                                null -> true
                                KEEPASS_GROUP_UNCATEGORIZED -> entry.keepassGroupPath.isNullOrBlank()
                                else -> entry.keepassGroupPath == selectedKeePassGroupPath
                            }
                    }
                    AutofillStorageSourceFilter.BITWARDEN -> {
                        val vaultId = entry.bitwardenVaultId
                        vaultId != null &&
                            (selectedVaultId == null || vaultId == selectedVaultId) &&
                            when (selectedFolderId) {
                                null -> true
                                BITWARDEN_FOLDER_UNCATEGORIZED -> entry.bitwardenFolderId.isNullOrBlank()
                                else -> entry.bitwardenFolderId == selectedFolderId
                            }
                    }
                }
            }
        }
    }

    val filteredPasswords by remember(sourceFilteredPasswords, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                sourceFilteredPasswords
            } else {
                sourceFilteredPasswords.filter { it.matchesSearchQuery(searchQuery) }
            }
        }
    }

    val filteredBankCards by remember(
        parsedBankCards,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedKeePassGroupPath,
        selectedVaultId,
        selectedFolderId,
        searchQuery
    ) {
        derivedStateOf {
            parsedBankCards.filter { (item, data) ->
                item.matchesAutofillSourceFilter(
                    sourceFilter = sourceFilter,
                    selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                    selectedKeePassGroupPath = selectedKeePassGroupPath,
                    selectedVaultId = selectedVaultId,
                    selectedFolderId = selectedFolderId
                ) && (searchQuery.isBlank() || data.matchesAutofillSearch(searchQuery))
            }
        }
    }

    val filteredDocuments by remember(
        parsedDocuments,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedKeePassGroupPath,
        selectedVaultId,
        selectedFolderId,
        searchQuery
    ) {
        derivedStateOf {
            parsedDocuments.filter { (item, data) ->
                item.matchesAutofillSourceFilter(
                    sourceFilter = sourceFilter,
                    selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                    selectedKeePassGroupPath = selectedKeePassGroupPath,
                    selectedVaultId = selectedVaultId,
                    selectedFolderId = selectedFolderId
                ) && (searchQuery.isBlank() || data.matchesAutofillSearch(searchQuery))
            }
        }
    }

    val filteredBillingAddresses by remember(
        parsedBillingAddresses,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedKeePassGroupPath,
        selectedVaultId,
        selectedFolderId,
        searchQuery
    ) {
        derivedStateOf {
            parsedBillingAddresses.filter { (item, data) ->
                item.matchesAutofillSourceFilter(
                    sourceFilter = sourceFilter,
                    selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                    selectedKeePassGroupPath = selectedKeePassGroupPath,
                    selectedVaultId = selectedVaultId,
                    selectedFolderId = selectedFolderId
                ) && (searchQuery.isBlank() || data.matchesAutofillSearch(searchQuery))
            }
        }
    }

    val filteredPasswordIds = remember(filteredPasswords) { filteredPasswords.map { it.id }.toHashSet() }
    val suggestedHiddenByCurrentFilter by remember(suggestedPasswords, filteredPasswordIds) {
        derivedStateOf {
            suggestedPasswords.count { it.id !in filteredPasswordIds }
        }
    }
    val visibleSuggestedPasswords = if (searchQuery.isBlank()) {
        suggestedPasswords
    } else {
        emptyList()
    }

    val filterSummary = when (sourceFilter) {
        AutofillStorageSourceFilter.ALL -> stringResource(R.string.filter_all)
        AutofillStorageSourceFilter.LOCAL -> stringResource(R.string.filter_local_only)
        AutofillStorageSourceFilter.KEEPASS -> {
            val databaseLabel = selectedKeePassDatabaseId?.let { keepassNameById[it] }
                ?: stringResource(R.string.password_picker_all_databases)
            val groupLabel = when (selectedKeePassGroupPath) {
                null -> null
                KEEPASS_GROUP_UNCATEGORIZED -> stringResource(R.string.category_none)
                else -> selectedKeePassGroupPath
            }
            if (groupLabel.isNullOrBlank()) {
                "${stringResource(R.string.filter_keepass)} · $databaseLabel"
            } else {
                "${stringResource(R.string.filter_keepass)} · $databaseLabel · $groupLabel"
            }
        }
        AutofillStorageSourceFilter.BITWARDEN -> {
            val vaultLabel = selectedVaultId?.let { vaultLabelById[it] }
                ?: stringResource(R.string.password_picker_all_vaults)
            val folderLabel = when (selectedFolderId) {
                null -> null
                BITWARDEN_FOLDER_UNCATEGORIZED -> stringResource(R.string.category_none)
                else -> selectedFolderId?.let { folderNameById[it] }
            }
            if (folderLabel.isNullOrBlank()) {
                "${stringResource(R.string.filter_bitwarden)} · $vaultLabel"
            } else {
                "${stringResource(R.string.filter_bitwarden)} · $vaultLabel · $folderLabel"
            }
        }
    }

    val activeFilterCount = when (sourceFilter) {
        AutofillStorageSourceFilter.ALL -> 0
        AutofillStorageSourceFilter.LOCAL -> 1
        AutofillStorageSourceFilter.KEEPASS -> 1 +
            (if (selectedKeePassDatabaseId != null) 1 else 0) +
            (if (selectedKeePassGroupPath != null) 1 else 0)
        AutofillStorageSourceFilter.BITWARDEN -> 1 +
            (if (selectedVaultId != null) 1 else 0) +
            (if (selectedFolderId != null) 1 else 0)
    }

    val filterTokens = when (sourceFilter) {
        AutofillStorageSourceFilter.ALL -> listOf(stringResource(R.string.filter_all))
        AutofillStorageSourceFilter.LOCAL -> listOf(stringResource(R.string.filter_local_only))
        AutofillStorageSourceFilter.KEEPASS -> buildList {
            add(stringResource(sourceFilter.labelResId()))
            selectedKeePassDatabaseId?.let { keepassNameById[it] }?.let(::add)
            when (selectedKeePassGroupPath) {
                null -> Unit
                KEEPASS_GROUP_UNCATEGORIZED -> add(stringResource(R.string.category_none))
                else -> selectedKeePassGroupPath?.let { add(it) }
            }
        }
        AutofillStorageSourceFilter.BITWARDEN -> buildList {
            add(stringResource(sourceFilter.labelResId()))
            selectedVaultId?.let { vaultLabelById[it] }?.let(::add)
            when (selectedFolderId) {
                null -> Unit
                BITWARDEN_FOLDER_UNCATEGORIZED -> add(stringResource(R.string.category_none))
                else -> {
                    val folderLabel = folderNameById[selectedFolderId]
                        .orEmpty()
                        .ifBlank { selectedFolderId ?: "" }
                    if (folderLabel.isNotBlank()) add(folderLabel)
                }
            }
        }
    }.filter { it.isNotBlank() }

    LaunchedEffect(
        isLoading,
        isSearchLoading,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedKeePassGroupPath,
        selectedVaultId,
        selectedFolderId,
        searchQuery.length,
        allPasswords.size,
        allBankCards.size,
        allDocuments.size,
        allBillingAddresses.size,
        suggestedPasswords.size,
        filteredPasswords.size,
        visibleSuggestedPasswords.size,
        suggestedHiddenByCurrentFilter,
        filteredBankCards.size,
        filteredDocuments.size,
        filteredBillingAddresses.size
    ) {
        AutofillLogger.d(
            "PICKER_UI",
            "UI state snapshot",
            metadata = mapOf(
                "isLoading" to isLoading,
                "isSearchLoading" to isSearchLoading,
                "queryLen" to searchQuery.length,
                "sourceFilter" to sourceFilter.name,
                "allCount" to allPasswords.size,
                "bankCardCount" to allBankCards.size,
                "documentCount" to allDocuments.size,
                "billingAddressCount" to allBillingAddresses.size,
                "suggestedCount" to suggestedPasswords.size,
                "filteredCount" to filteredPasswords.size,
                "visibleSuggestedCount" to visibleSuggestedPasswords.size,
                "suggestedHiddenByCurrentFilter" to suggestedHiddenByCurrentFilter,
                "filteredBankCards" to filteredBankCards.size,
                "filteredDocuments" to filteredDocuments.size,
                "filteredBillingAddresses" to filteredBillingAddresses.size,
                "selectedKeePassDb" to (selectedKeePassDatabaseId ?: -1L),
                "selectedKeePassGroupSet" to (selectedKeePassGroupPath != null),
                "selectedVaultId" to (selectedVaultId ?: -1L),
                "selectedFolderSet" to (selectedFolderId != null)
            )
        )
    }

    val application = context.applicationContext as Application
    val autofillPasswordViewModel: PasswordViewModel = viewModel(
        factory = remember(repository, securityManager, secureItemRepository, customFieldRepository, appDb, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PasswordViewModel::class.java)) {
                        return PasswordViewModel(
                            repository = repository,
                            securityManager = securityManager,
                            secureItemRepository = secureItemRepository,
                            customFieldRepository = customFieldRepository,
                            context = context.applicationContext,
                            localKeePassDatabaseDao = appDb.localKeePassDatabaseDao(),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    )
    val autofillLocalKeePassViewModel: LocalKeePassViewModel = viewModel(
        factory = remember(appDb, securityManager, application) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LocalKeePassViewModel::class.java)) {
                        return LocalKeePassViewModel(
                            application = application,
                            dao = appDb.localKeePassDatabaseDao(),
                            securityManager = securityManager,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    )
    val autofillBankCardViewModel: BankCardViewModel = viewModel(
        factory = remember(secureItemRepository, appDb, securityManager, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(BankCardViewModel::class.java)) {
                        return BankCardViewModel(
                            repository = secureItemRepository,
                            context = context.applicationContext,
                            localKeePassDatabaseDao = appDb.localKeePassDatabaseDao(),
                            securityManager = securityManager,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    )
    val autofillDocumentViewModel: DocumentViewModel = viewModel(
        factory = remember(secureItemRepository, appDb, securityManager, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DocumentViewModel::class.java)) {
                        return DocumentViewModel(
                            repository = secureItemRepository,
                            context = context.applicationContext,
                            localKeePassDatabaseDao = appDb.localKeePassDatabaseDao(),
                            securityManager = securityManager,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    )
    val autofillBillingAddressViewModel: BillingAddressViewModel = viewModel(
        factory = remember(secureItemRepository, securityManager) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(BillingAddressViewModel::class.java)) {
                        return BillingAddressViewModel(
                            repository = secureItemRepository,
                            securityManager = securityManager,
                            context = context.applicationContext,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    )
    val addMenuActions = remember(addTargets, generatedPasswordOptions) {
        buildList {
            add(
                AutofillFabMenuAction(
                    icon = Icons.Default.Password,
                    labelRes = R.string.autofill_generate_password,
                    onClick = {
                        useGeneratedPasswordForNextAdd = false
                        generatedPassword = generateAutofillPassword(generatedPasswordOptions)
                        showGeneratedPasswordSheet = true
                    }
                )
            )
            addAll(addTargets.map { target ->
                when (target) {
                    AutofillAddTarget.PASSWORD -> AutofillFabMenuAction(
                        icon = Icons.Default.Lock,
                        labelRes = R.string.item_type_password,
                        onClick = {
                            useGeneratedPasswordForNextAdd = false
                            pendingAddTarget = AutofillAddTarget.PASSWORD
                            currentScreen = "add"
                        }
                    )
                    AutofillAddTarget.DOCUMENT -> AutofillFabMenuAction(
                        icon = Icons.Default.Badge,
                        labelRes = R.string.item_type_document,
                        onClick = { pendingAddTarget = AutofillAddTarget.DOCUMENT; currentScreen = "add" }
                    )
                    AutofillAddTarget.BANK_CARD -> AutofillFabMenuAction(
                        icon = Icons.Default.CreditCard,
                        labelRes = R.string.item_type_bank_card,
                        onClick = { pendingAddTarget = AutofillAddTarget.BANK_CARD; currentScreen = "add" }
                    )
                    AutofillAddTarget.BILLING_ADDRESS -> AutofillFabMenuAction(
                        icon = Icons.Default.Home,
                        labelRes = R.string.add_billing_address,
                        onClick = { pendingAddTarget = AutofillAddTarget.BILLING_ADDRESS; currentScreen = "add" }
                    )
                }
            })
        }
    }
    val handleBankCardClick: (SecureItem, BankCardData) -> Unit = { item, data ->
        if (canDirectFillBankCard) {
            onAutofillBankCard(item)
        } else {
            structuredCopyDialog = StructuredAutofillCopyDialogState.BankCard(item, data)
        }
    }
    val handleDocumentClick: (SecureItem, DocumentData) -> Unit = { item, data ->
        if (canDirectFillDocument) {
            onAutofillDocument(item)
        } else {
            structuredCopyDialog = StructuredAutofillCopyDialogState.Document(item, data)
        }
    }
    val handleBillingAddressClick: (SecureItem, BillingAddressData) -> Unit = { item, data ->
        if (canDirectFillBillingAddress) {
            onAutofillBillingAddress(item)
        } else {
            structuredCopyDialog = StructuredAutofillCopyDialogState.BillingAddress(item, data)
        }
    }
    val navigateBackToList: () -> Unit = {
        pendingAddTarget = null
        currentScreen = "list"
    }

    if (showMarkAsNonAutofillDialog && canMarkAsNonAutofill) {
        AlertDialog(
            onDismissRequest = { showMarkAsNonAutofillDialog = false },
            title = {
                Text(text = stringResource(R.string.autofill_mark_not_field_dialog_title))
            },
            text = {
                Text(
                    text = if (markNonAutofillTargetLabel.isNotBlank()) {
                        stringResource(
                            R.string.autofill_mark_not_field_dialog_message_with_target,
                            markNonAutofillTargetLabel,
                        )
                    } else {
                        stringResource(R.string.autofill_mark_not_field_dialog_message)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMarkAsNonAutofillDialog = false
                        onMarkAsNonAutofill()
                    }
                ) {
                    Text(text = stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkAsNonAutofillDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    structuredCopyDialog?.let { dialogState ->
        StructuredAutofillCopyDialog(
            state = dialogState,
            detectedTypeLabel = detectedAutofillTypeLabel,
            onCopy = { action ->
                structuredCopyDialog = null
                onCopy(action.label, action.value, action.sensitive)
            },
            onDismiss = { structuredCopyDialog = null }
        )
    }

    if (showGeneratedPasswordSheet) {
        GeneratedPasswordBottomSheet(
            password = generatedPassword,
            options = generatedPasswordOptions,
            onRefresh = { generatedPassword = generateAutofillPassword(generatedPasswordOptions) },
            onLengthChange = { length ->
                generatedPasswordLength = length
                generatedPassword = generateAutofillPassword(generatedPasswordOptions.copy(length = length))
            },
            onUppercaseChange = { enabled ->
                generatedIncludeUppercase = enabled
                generatedPassword = generateAutofillPassword(generatedPasswordOptions.copy(includeUppercase = enabled))
            },
            onLowercaseChange = { enabled ->
                generatedIncludeLowercase = enabled
                generatedPassword = generateAutofillPassword(generatedPasswordOptions.copy(includeLowercase = enabled))
            },
            onNumbersChange = { enabled ->
                generatedIncludeNumbers = enabled
                generatedPassword = generateAutofillPassword(generatedPasswordOptions.copy(includeNumbers = enabled))
            },
            onSymbolsChange = { enabled ->
                generatedIncludeSymbols = enabled
                generatedPassword = generateAutofillPassword(generatedPasswordOptions.copy(includeSymbols = enabled))
            },
            onReadableModeChange = { enabled ->
                generatedExcludeSimilar = enabled
                generatedExcludeAmbiguous = enabled
                generatedPassword = generateAutofillPassword(
                    generatedPasswordOptions.copy(
                        excludeSimilar = enabled,
                        excludeAmbiguous = enabled
                    )
                )
            },
            onCopy = {
                onCopy(autofillPasswordLabel, generatedPassword, true)
                showGeneratedPasswordSheet = false
            },
            onFill = {
                showGeneratedPasswordSheet = false
                onFillGeneratedPassword(generatedPassword)
            },
            onSaveAsNew = {
                showGeneratedPasswordSheet = false
                useGeneratedPasswordForNextAdd = true
                pendingAddTarget = AutofillAddTarget.PASSWORD
                currentScreen = "add"
            },
            onDismiss = { showGeneratedPasswordSheet = false }
        )
    }
    
    // 根据当前屏幕显示内容 - 带动画过渡
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState == "list") {
                // 返回列表：从左滑入
                (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(tween(300)))
            } else {
                // 进入子页面：从右滑入
                (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(tween(300)))
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
        "list" -> {
            // 根据模式显示不同标题
            val showTargetContext = !isManualMode || showTargetContextInManualMode
            val title = when {
                args.isSaveMode -> stringResource(R.string.autofill_save_form_data)
                isManualMode && !showTargetContextInManualMode -> stringResource(R.string.autofill_manual_quick_copy)
                else -> stringResource(R.string.autofill_with_monica)
            }
            
            AutofillScaffold(
                topBar = {
                    AutofillHeader(
                        title = title,
                        username = if (showTargetContext) args.capturedUsername else null,
                        password = if (showTargetContext) args.capturedPassword else null,
                        applicationId = if (showTargetContext) args.applicationId else null,
                        webDomain = if (showTargetContext) args.webDomain else null,
                        appIcon = if (showTargetContext) appIcon else null,
                        appName = if (showTargetContext) appName else stringResource(R.string.autofill_select_password_and_copy),
                        onClose = onClose
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AutofillExpressiveSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            showMarkButton = canMarkAsNonAutofill,
                            onMarkClick = { showMarkAsNonAutofillDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        AutofillFilterTrigger(
                            sourceFilter = sourceFilter,
                            summary = filterSummary,
                            chips = filterTokens,
                            activeFilterCount = activeFilterCount,
                            expanded = isFilterExpanded,
                            onClick = { isFilterExpanded = !isFilterExpanded },
                            panelContent = {
                                AutofillFilterPanelBody(
                                    sourceFilter = sourceFilter,
                                    activeFilterCount = activeFilterCount,
                                    keepassDatabases = keepassDatabases,
                                    keepassNameById = keepassNameById,
                                    selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                                    selectedKeePassGroupPath = selectedKeePassGroupPath,
                                    keepassGroupsForSelectedDb = keepassGroupsForSelectedDb,
                                    hasUncategorizedKeepassEntries = hasUncategorizedKeepassEntries,
                                    bitwardenVaults = bitwardenVaults,
                                    vaultLabelById = vaultLabelById,
                                    selectedVaultId = selectedVaultId,
                                    selectedFolderId = selectedFolderId,
                                    selectedVaultFolders = selectedVaultFolders,
                                    folderNameById = folderNameById,
                                    hasUncategorizedBitwardenEntries = hasUncategorizedBitwardenEntries,
                                    onSourceFilterChange = { newSource ->
                                        sourceFilter = newSource
                                        when (newSource) {
                                            AutofillStorageSourceFilter.ALL,
                                            AutofillStorageSourceFilter.LOCAL -> {
                                                selectedKeePassDatabaseId = null
                                                selectedKeePassGroupPath = null
                                                selectedVaultId = null
                                                selectedFolderId = null
                                            }
                                            AutofillStorageSourceFilter.KEEPASS -> {
                                                selectedVaultId = null
                                                selectedFolderId = null
                                            }
                                            AutofillStorageSourceFilter.BITWARDEN -> {
                                                selectedKeePassDatabaseId = null
                                                selectedKeePassGroupPath = null
                                            }
                                        }
                                        persistPickerDefaults()
                                    },
                                    onSelectKeePassDatabase = { databaseId ->
                                        selectedKeePassDatabaseId = databaseId
                                        selectedKeePassGroupPath = null
                                        persistPickerDefaults()
                                    },
                                    onSelectKeePassGroup = { groupPath ->
                                        selectedKeePassGroupPath = groupPath
                                    },
                                    onSelectVault = { vaultId ->
                                        selectedVaultId = vaultId
                                        selectedFolderId = null
                                        persistPickerDefaults()
                                    },
                                    onSelectFolder = { folderId ->
                                        selectedFolderId = folderId
                                    },
                                    onResetAllFilters = {
                                        sourceFilter = AutofillStorageSourceFilter.ALL
                                        selectedKeePassDatabaseId = null
                                        selectedKeePassGroupPath = null
                                        selectedVaultId = null
                                        selectedFolderId = null
                                        persistPickerDefaults()
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        
                        AutofillPickerContentTypeTabs(
                            selectedType = selectedContentType,
                            passwordCount = filteredPasswords.size,
                            bankCardCount = filteredBankCards.size,
                            documentCount = filteredDocuments.size,
                            billingAddressCount = filteredBillingAddresses.size,
                            onSelectType = { selectedContentType = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                PasswordListInitialLoadingIndicator()
                            }
                        } else {
                            when (selectedContentType) {
                                AutofillPickerContentType.ACCOUNT -> {
                                    val showSuggestedSection = searchQuery.isBlank() &&
                                        visibleSuggestedPasswords.isNotEmpty()
                                    val suggestedIds = visibleSuggestedPasswords.map { it.id }.toSet()
                                    val mainPasswords = if (showSuggestedSection) {
                                        filteredPasswords.filter { it.id !in suggestedIds }
                                    } else {
                                        filteredPasswords
                                    }
                                    val showNoSuggestionsHint = sourceFilter == AutofillStorageSourceFilter.ALL &&
                                        selectedKeePassDatabaseId == null &&
                                        selectedKeePassGroupPath == null &&
                                        selectedVaultId == null &&
                                        selectedFolderId == null &&
                                        searchQuery.isBlank() &&
                                        visibleSuggestedPasswords.isEmpty()

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .weight(1f)
                                    ) {
                                        if (isSearchLoading) {
                                            item(key = "search_loading") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                        }

                                        if (showSuggestedSection) {
                                            item {
                                                Text(
                                                    text = stringResource(R.string.autofill_suggested_fill),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }

                                            items(
                                                items = visibleSuggestedPasswords,
                                                key = { "suggested_${it.id}" }
                                            ) { password ->
                                                SuggestedPasswordListItem(
                                                    password = password,
                                                    iconCardsEnabled = iconCardsEnabled,
                                                    showSmartCopyOptions = hasNotificationPermission,
                                                    onPrepareAutofill = onPrepareAutofill,
                                                    onAction = handlePasswordAction
                                                )
                                            }

                                            item {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant
                                                )
                                            }
                                        }

                                        if (showNoSuggestionsHint) {
                                            item {
                                                NoSuggestionsHint()
                                            }
                                        }

                                        if (mainPasswords.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = if (showSuggestedSection) {
                                                        stringResource(R.string.autofill_other_entries)
                                                    } else {
                                                        when (sourceFilter) {
                                                            AutofillStorageSourceFilter.ALL -> stringResource(R.string.autofill_all_entries)
                                                            AutofillStorageSourceFilter.LOCAL -> stringResource(R.string.filter_local_only)
                                                            AutofillStorageSourceFilter.KEEPASS -> stringResource(R.string.filter_keepass)
                                                            AutofillStorageSourceFilter.BITWARDEN -> stringResource(R.string.filter_bitwarden)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }
                                        } else if (!showNoSuggestionsHint) {
                                            item {
                                                EmptyPasswordState(
                                                    modifier = Modifier
                                                        .fillParentMaxSize()
                                                        .padding(top = 32.dp)
                                                )
                                            }
                                        }

                                        items(
                                            items = mainPasswords,
                                            key = { it.id }
                                        ) { password ->
                                            PasswordListItem(
                                                password = password,
                                                showDropdownMenu = true,
                                                iconCardsEnabled = iconCardsEnabled,
                                                showSmartCopyOptions = hasNotificationPermission,
                                                onPrepareAutofill = onPrepareAutofill,
                                                onAction = handlePasswordAction
                                            )
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(80.dp))
                                        }
                                    }
                                }
                                AutofillPickerContentType.PAYMENT -> {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .weight(1f)
                                    ) {
                                        if (filteredBankCards.isNotEmpty()) {
                                            item(key = "cards_header") {
                                                Text(
                                                    text = stringResource(R.string.item_type_bank_card),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }
                                            items(
                                                items = filteredBankCards,
                                                key = { (item, _) -> "card_${item.id}" }
                                            ) { (item, data) ->
                                                AutofillStructuredItemCard(
                                                    title = bankCardDisplayTitle(item, data),
                                                    subtitle = bankCardDisplaySubtitle(data),
                                                    billingAddress = bankCardBillingAddressDisplay(data),
                                                    onClick = { handleBankCardClick(item, data) }
                                                )
                                            }
                                        } else {
                                            item {
                                                EmptyPasswordState(
                                                    modifier = Modifier
                                                        .fillParentMaxSize()
                                                        .padding(top = 32.dp)
                                                )
                                            }
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(80.dp))
                                        }
                                    }
                                }
                                AutofillPickerContentType.DOCUMENT -> {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .weight(1f)
                                    ) {
                                        if (filteredDocuments.isNotEmpty()) {
                                            item(key = "documents_header") {
                                                Text(
                                                    text = stringResource(R.string.item_type_document),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }
                                            items(
                                                items = filteredDocuments,
                                                key = { (item, _) -> "document_${item.id}" }
                                            ) { (item, data) ->
                                                AutofillStructuredItemCard(
                                                    title = documentDisplayTitle(item, data),
                                                    subtitle = documentDisplaySubtitle(data),
                                                    billingAddress = documentBillingAddressDisplay(data),
                                                    onClick = { handleDocumentClick(item, data) }
                                                )
                                            }
                                        } else {
                                            item {
                                                EmptyPasswordState(
                                                    modifier = Modifier
                                                        .fillParentMaxSize()
                                                        .padding(top = 32.dp)
                                                )
                                            }
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(80.dp))
                                        }
                                    }
                                }
                                AutofillPickerContentType.BILLING_ADDRESS -> {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .weight(1f)
                                    ) {
                                        if (filteredBillingAddresses.isNotEmpty()) {
                                            item(key = "billing_addresses_header") {
                                                Text(
                                                    text = stringResource(R.string.billing_address),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }
                                            items(
                                                items = filteredBillingAddresses,
                                                key = { (item, _) -> "billing_address_${item.id}" }
                                            ) { (item, data) ->
                                                AutofillStructuredItemCard(
                                                    title = billingAddressDisplayTitle(item, data),
                                                    subtitle = billingAddressDisplaySubtitle(data),
                                                    billingAddress = data.toAutofillAddressForPicker(),
                                                    onClick = { handleBillingAddressClick(item, data) }
                                                )
                                            }
                                        } else {
                                            item {
                                                EmptyPasswordState(
                                                    modifier = Modifier
                                                        .fillParentMaxSize()
                                                        .padding(top = 32.dp)
                                                )
                                            }
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(80.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (addMenuActions.isNotEmpty()) {
                        AutofillFabMenu(
                            menuActions = addMenuActions,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
        
        "detail" -> {
            selectedPassword?.let { password ->
                InlinePasswordDetailContent(
                    password = password,
                    securityManager = securityManager,
                    onAutofill = { onAutofill(password, false) },
                    onAutofillAndSaveUri = { onAutofill(password, true) },
                    onBack = { currentScreen = "list" }
                )
            }
        }
        
        "add" -> {
            val isWebFlow = !args.webDomain.isNullOrBlank()
            val initialTitle = when {
                isWebFlow -> args.webDomain.orEmpty()
                !appName.isNullOrBlank() -> appName
                else -> args.applicationId.orEmpty()
            }.orEmpty()
            when (pendingAddTarget ?: defaultAddTarget) {
                AutofillAddTarget.PASSWORD -> {
                    AddEditPasswordScreen(
                        viewModel = autofillPasswordViewModel,
                        localKeePassViewModel = autofillLocalKeePassViewModel,
                        passwordId = null,
                        initialDraft = AddEditPasswordInitialDraft(
                            title = initialTitle,
                            website = args.webDomain.orEmpty(),
                            username = args.capturedUsername.orEmpty(),
                            password = generatedPassword.takeIf {
                                useGeneratedPasswordForNextAdd &&
                                    pendingAddTarget == AutofillAddTarget.PASSWORD
                            } ?: args.capturedPassword.orEmpty(),
                            appPackageName = if (isWebFlow) "" else args.applicationId.orEmpty(),
                            appName = if (isWebFlow) "" else appName.orEmpty(),
                        ),
                        forceShowAppBinding = true,
                        onSaveCompleted = { firstPasswordId ->
                            if (firstPasswordId == null) {
                                navigateBackToList()
                                return@AddEditPasswordScreen
                            }
                            coroutineScope.launch {
                                val savedEntry = repository.getPasswordEntryById(firstPasswordId)
                                if (savedEntry != null) {
                                    onAutofill(savedEntry, true)
                                } else {
                                    navigateBackToList()
                                }
                            }
                        },
                        onNavigateBack = navigateBackToList
                    )
                }
                AutofillAddTarget.DOCUMENT -> {
                    AddEditDocumentScreen(
                        viewModel = autofillDocumentViewModel,
                        documentId = null,
                        onNavigateBack = navigateBackToList
                    )
                }
                AutofillAddTarget.BANK_CARD -> {
                    AddEditBankCardScreen(
                        viewModel = autofillBankCardViewModel,
                        cardId = null,
                        onNavigateBack = navigateBackToList
                    )
                }
                AutofillAddTarget.BILLING_ADDRESS -> {
                    AddEditBillingAddressScreen(
                        viewModel = autofillBillingAddressViewModel,
                        addressId = null,
                        onNavigateBack = navigateBackToList
                    )
                }
            }
        }
        }
    }



}

@Composable
private fun AutofillStructuredItemCard(
    title: String,
    subtitle: String,
    billingAddress: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title.ifBlank { "-" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (billingAddress.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    text = stringResource(R.string.billing_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = billingAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class AutofillStorageSourceFilter {
    ALL,
    LOCAL,
    KEEPASS,
    BITWARDEN
}

private enum class AutofillPickerContentType {
    ACCOUNT,
    PAYMENT,
    DOCUMENT,
    BILLING_ADDRESS
}

@Composable
private fun AutofillPickerContentTypeTabs(
    selectedType: AutofillPickerContentType,
    passwordCount: Int,
    bankCardCount: Int,
    documentCount: Int,
    billingAddressCount: Int,
    onSelectType: (AutofillPickerContentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AutofillPickerContentTypeChip(
            selected = selectedType == AutofillPickerContentType.ACCOUNT,
            icon = Icons.Default.Lock,
            label = stringResource(R.string.item_type_password),
            count = passwordCount,
            onClick = { onSelectType(AutofillPickerContentType.ACCOUNT) }
        )
        AutofillPickerContentTypeChip(
            selected = selectedType == AutofillPickerContentType.PAYMENT,
            icon = Icons.Default.CreditCard,
            label = stringResource(R.string.item_type_bank_card),
            count = bankCardCount,
            onClick = { onSelectType(AutofillPickerContentType.PAYMENT) }
        )
        AutofillPickerContentTypeChip(
            selected = selectedType == AutofillPickerContentType.DOCUMENT,
            icon = Icons.Default.Badge,
            label = stringResource(R.string.item_type_document),
            count = documentCount,
            onClick = { onSelectType(AutofillPickerContentType.DOCUMENT) }
        )
        AutofillPickerContentTypeChip(
            selected = selectedType == AutofillPickerContentType.BILLING_ADDRESS,
            icon = Icons.Default.Home,
            label = stringResource(R.string.billing_address),
            count = billingAddressCount,
            onClick = { onSelectType(AutofillPickerContentType.BILLING_ADDRESS) }
        )
    }
}

@Composable
private fun AutofillPickerContentTypeChip(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        label = {
            Text(
                text = "$label $count",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

private fun AutofillStorageSourceFilter.toPreferenceFilter(): AutofillPreferences.AutofillDefaultSourceFilter {
    return when (this) {
        AutofillStorageSourceFilter.ALL -> AutofillPreferences.AutofillDefaultSourceFilter.ALL
        AutofillStorageSourceFilter.LOCAL -> AutofillPreferences.AutofillDefaultSourceFilter.LOCAL
        AutofillStorageSourceFilter.KEEPASS -> AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS
        AutofillStorageSourceFilter.BITWARDEN -> AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN
    }
}

private fun AutofillPreferences.AutofillDefaultSourceFilter.toUiFilter(): AutofillStorageSourceFilter {
    return when (this) {
        AutofillPreferences.AutofillDefaultSourceFilter.ALL -> AutofillStorageSourceFilter.ALL
        AutofillPreferences.AutofillDefaultSourceFilter.LOCAL -> AutofillStorageSourceFilter.LOCAL
        AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS -> AutofillStorageSourceFilter.KEEPASS
        AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN -> AutofillStorageSourceFilter.BITWARDEN
    }
}

private sealed interface StructuredAutofillCopyDialogState {
    data class BankCard(
        val item: SecureItem,
        val data: BankCardData,
    ) : StructuredAutofillCopyDialogState

    data class Document(
        val item: SecureItem,
        val data: DocumentData,
    ) : StructuredAutofillCopyDialogState

    data class BillingAddress(
        val item: SecureItem,
        val data: BillingAddressData,
    ) : StructuredAutofillCopyDialogState
}

private data class StructuredCopyAction(
    val label: String,
    val value: String,
    val displayValue: String = value,
    val sensitive: Boolean = false,
)

@Composable
private fun StructuredAutofillCopyDialog(
    state: StructuredAutofillCopyDialogState,
    detectedTypeLabel: String,
    onCopy: (StructuredCopyAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val targetTypeLabel = when (state) {
        is StructuredAutofillCopyDialogState.BankCard -> stringResource(R.string.item_type_bank_card)
        is StructuredAutofillCopyDialogState.Document -> stringResource(R.string.item_type_document)
        is StructuredAutofillCopyDialogState.BillingAddress -> stringResource(R.string.billing_address)
    }
    val title = when (state) {
        is StructuredAutofillCopyDialogState.BankCard -> bankCardDisplayTitle(state.item, state.data)
        is StructuredAutofillCopyDialogState.Document -> documentDisplayTitle(state.item, state.data)
        is StructuredAutofillCopyDialogState.BillingAddress -> billingAddressDisplayTitle(state.item, state.data)
    }
    val subtitle = when (state) {
        is StructuredAutofillCopyDialogState.BankCard -> bankCardDisplaySubtitle(state.data)
        is StructuredAutofillCopyDialogState.Document -> documentDisplaySubtitle(state.data)
        is StructuredAutofillCopyDialogState.BillingAddress -> billingAddressDisplaySubtitle(state.data)
    }
    val actions = when (state) {
        is StructuredAutofillCopyDialogState.BankCard -> bankCardCopyActions(state.data)
        is StructuredAutofillCopyDialogState.Document -> documentCopyActions(state.data)
        is StructuredAutofillCopyDialogState.BillingAddress -> billingAddressCopyActions(state.data)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title.ifBlank { targetTypeLabel },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(
                        R.string.autofill_structured_copy_message,
                        detectedTypeLabel,
                        targetTypeLabel,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = stringResource(R.string.autofill_structured_fill_unavailable))
                }
                actions.forEach { action ->
                    StructuredCopyActionButton(
                        action = action,
                        onClick = { onCopy(action) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun StructuredCopyActionButton(
    action: StructuredCopyAction,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${stringResource(R.string.copy)} ${action.label}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = action.displayValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun bankCardCopyActions(data: BankCardData): List<StructuredCopyAction> {
    val expiry = bankCardExpiryCopyValue(data)
    return buildList {
        addCopyAction(stringResource(R.string.card_number), data.cardNumber, maskBankCardNumber(data.cardNumber), true)
        addCopyAction(stringResource(R.string.cardholder_label), data.cardholderName)
        addCopyAction(stringResource(R.string.expiry_label), expiry)
        addCopyAction(stringResource(R.string.cvv), data.cvv, sensitive = true)
        addCopyAction(stringResource(R.string.bank_name), data.bankName)
        addCopyAction(stringResource(R.string.bank_card_brand_label), data.brand)
        addCopyAction(stringResource(R.string.bank_card_nickname_label), data.nickname)
        addCopyAction(stringResource(R.string.billing_address), bankCardBillingAddressDisplay(data))
        addCopyAction(stringResource(R.string.bank_card_customer_service_phone_label), data.customerServicePhone)
    }
}

@Composable
private fun documentCopyActions(data: DocumentData): List<StructuredCopyAction> {
    return buildList {
        addCopyAction(stringResource(R.string.document_number_label), data.documentNumber, maskDocumentNumber(data.documentNumber), true)
        addCopyAction(stringResource(R.string.full_name), data.displayFullName().ifBlank { data.fullName })
        addCopyAction(stringResource(R.string.document_first_name_label), data.firstName)
        addCopyAction(stringResource(R.string.document_last_name_label), data.lastName)
        addCopyAction(stringResource(R.string.email), data.email)
        addCopyAction(stringResource(R.string.phone), data.phone)
        addCopyAction(stringResource(R.string.billing_address), documentBillingAddressDisplay(data))
        addCopyAction(stringResource(R.string.document_passport_number_label), data.passportNumber, maskDocumentNumber(data.passportNumber), true)
        addCopyAction(stringResource(R.string.document_license_number_label), data.licenseNumber, maskDocumentNumber(data.licenseNumber), true)
        addCopyAction(stringResource(R.string.document_ssn_label), data.ssn, maskDocumentNumber(data.ssn), true)
        addCopyAction(stringResource(R.string.issued_date), data.issuedDate)
        addCopyAction(stringResource(R.string.expiry_date_label), data.expiryDate)
        addCopyAction(stringResource(R.string.issued_by), data.issuedBy)
        addCopyAction(stringResource(R.string.nationality), data.nationality)
        addCopyAction(stringResource(R.string.document_company_label), data.company)
        addCopyAction(stringResource(R.string.autofill_username), data.username)
    }
}

@Composable
private fun billingAddressCopyActions(data: BillingAddressData): List<StructuredCopyAction> {
    return buildList {
        addCopyAction(stringResource(R.string.billing_address), data.formatForDisplay())
        addCopyAction(stringResource(R.string.full_name), data.fullName)
        addCopyAction(stringResource(R.string.document_company_label), data.company)
        addCopyAction(stringResource(R.string.street_address), data.streetAddress)
        addCopyAction(stringResource(R.string.apartment), data.apartment)
        addCopyAction(stringResource(R.string.city), data.city)
        addCopyAction(stringResource(R.string.state_province), data.stateProvince)
        addCopyAction(stringResource(R.string.postal_code), data.postalCode)
        addCopyAction(stringResource(R.string.country), data.country)
        addCopyAction(stringResource(R.string.phone), data.phone)
        addCopyAction(stringResource(R.string.email), data.email)
    }
}

private fun MutableList<StructuredCopyAction>.addCopyAction(
    label: String,
    value: String,
    displayValue: String = value,
    sensitive: Boolean = false,
) {
    val trimmed = value.trim()
    if (trimmed.isNotBlank()) {
        add(
            StructuredCopyAction(
                label = label,
                value = trimmed,
                displayValue = displayValue.trim().ifBlank { trimmed },
                sensitive = sensitive,
            )
        )
    }
}

private fun bankCardExpiryCopyValue(data: BankCardData): String {
    val month = data.expiryMonth.trim()
    val year = data.expiryYear.trim()
    return when {
        month.isBlank() -> year
        year.isBlank() -> month
        else -> "$month/$year"
    }
}

private data class AutofillFabMenuAction(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

internal data class AutofillPasswordGeneratorOptions(
    val length: Int = 12,
    val includeUppercase: Boolean = true,
    val includeLowercase: Boolean = true,
    val includeNumbers: Boolean = true,
    val includeSymbols: Boolean = true,
    val allowedSymbols: String? = null,
    val excludeSimilar: Boolean = false,
    val excludeAmbiguous: Boolean = false,
    val uppercaseMin: Int = 0,
    val lowercaseMin: Int = 0,
    val numbersMin: Int = 0,
    val symbolsMin: Int = 0,
) {
    val readableMode: Boolean
        get() = excludeSimilar || excludeAmbiguous
}

internal fun GeneratorPreferences.toAutofillPasswordGeneratorOptions(): AutofillPasswordGeneratorOptions {
    val sharedOptions = toSymbolPasswordGeneratorOptions()
    return AutofillPasswordGeneratorOptions(
        length = sharedOptions.length,
        includeUppercase = sharedOptions.includeUppercase,
        includeLowercase = sharedOptions.includeLowercase,
        includeNumbers = sharedOptions.includeNumbers,
        includeSymbols = sharedOptions.includeSymbols,
        allowedSymbols = sharedOptions.allowedSymbols,
        excludeSimilar = sharedOptions.excludeSimilar,
        excludeAmbiguous = sharedOptions.excludeAmbiguous,
        uppercaseMin = sharedOptions.uppercaseMin,
        lowercaseMin = sharedOptions.lowercaseMin,
        numbersMin = sharedOptions.numbersMin,
        symbolsMin = sharedOptions.symbolsMin,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratedPasswordBottomSheet(
    password: String,
    options: AutofillPasswordGeneratorOptions,
    onRefresh: () -> Unit,
    onLengthChange: (Int) -> Unit,
    onUppercaseChange: (Boolean) -> Unit,
    onLowercaseChange: (Boolean) -> Unit,
    onNumbersChange: (Boolean) -> Unit,
    onSymbolsChange: (Boolean) -> Unit,
    onReadableModeChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onFill: () -> Unit,
    onSaveAsNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Password,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.autofill_generated_password_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.regenerate)
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_password)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                SelectionContainer {
                    Text(
                        text = password,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSaveAsNew,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.save_as_new_entry))
                }
                Button(
                    onClick = onFill,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = stringResource(R.string.autofill_generated_password_fill))
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.autofill_generated_password_options),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AutofillPasswordLengthRow(
                        length = options.length,
                        onLengthChange = onLengthChange,
                    )
                    val selectedTypeCount = listOf(
                        options.includeUppercase,
                        options.includeLowercase,
                        options.includeNumbers,
                        options.includeSymbols,
                    ).count { it }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AutofillPasswordOptionSwitchRow(
                                text = stringResource(R.string.autofill_generated_password_uppercase),
                                checked = options.includeUppercase,
                                enabled = !options.includeUppercase || selectedTypeCount > 1,
                                onCheckedChange = onUppercaseChange,
                            )
                            AutofillPasswordOptionSwitchRow(
                                text = stringResource(R.string.autofill_generated_password_numbers),
                                checked = options.includeNumbers,
                                enabled = !options.includeNumbers || selectedTypeCount > 1,
                                onCheckedChange = onNumbersChange,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AutofillPasswordOptionSwitchRow(
                                text = stringResource(R.string.autofill_generated_password_lowercase),
                                checked = options.includeLowercase,
                                enabled = !options.includeLowercase || selectedTypeCount > 1,
                                onCheckedChange = onLowercaseChange,
                            )
                            AutofillPasswordOptionSwitchRow(
                                text = stringResource(R.string.autofill_generated_password_symbols),
                                checked = options.includeSymbols,
                                enabled = !options.includeSymbols || selectedTypeCount > 1,
                                onCheckedChange = onSymbolsChange,
                            )
                        }
                    }
                    AutofillPasswordOptionSwitchRow(
                        text = stringResource(R.string.autofill_generated_password_readable),
                        checked = options.readableMode,
                        enabled = true,
                        onCheckedChange = onReadableModeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutofillPasswordLengthRow(
    length: Int,
    onLengthChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.autofill_generated_password_length),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = length.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = length.toFloat(),
            onValueChange = { value -> onLengthChange(value.roundToInt().coerceIn(8, 32)) },
            valueRange = 8f..32f,
            steps = 23,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        )
    }
}

@Composable
private fun AutofillPasswordOptionSwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.height(36.dp)
        )
    }
}

private fun generateAutofillPassword(options: AutofillPasswordGeneratorOptions): String {
    return PasswordGenerator.generatePassword(
        length = options.length,
        includeUppercase = options.includeUppercase,
        includeLowercase = options.includeLowercase,
        includeNumbers = options.includeNumbers,
        includeSymbols = options.includeSymbols,
        allowedSymbols = options.allowedSymbols,
        excludeSimilar = options.excludeSimilar,
        excludeAmbiguous = options.excludeAmbiguous,
        uppercaseMin = if (options.includeUppercase) options.uppercaseMin else 0,
        lowercaseMin = if (options.includeLowercase) options.lowercaseMin else 0,
        numbersMin = if (options.includeNumbers) options.numbersMin else 0,
        symbolsMin = if (options.includeSymbols) options.symbolsMin else 0
    )
}

@Composable
private fun AutofillFabMenu(
    menuActions: List<AutofillFabMenuAction>,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 28.dp else 16.dp,
        animationSpec = spring(),
        label = "autofill_fab_corner"
    )

    fun updateExpanded(next: Boolean) {
        isExpanded = next
    }

    BackHandler(enabled = isExpanded) {
        updateExpanded(false)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
            modifier = Modifier.matchParentSize()
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        updateExpanded(false)
                    }
            )
        }

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            menuActions.forEachIndexed { index, action ->
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(
                        animationSpec = tween(durationMillis = 160, delayMillis = index * 28)
                    ) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = tween(
                            durationMillis = 220,
                            delayMillis = index * 28,
                            easing = LinearEasing
                        )
                    ),
                    exit = fadeOut(animationSpec = tween(durationMillis = 90)) + slideOutVertically(
                        targetOffsetY = { it / 4 },
                        animationSpec = tween(durationMillis = 120)
                    )
                ) {
                    Surface(
                        onClick = {
                            updateExpanded(false)
                            action.onClick()
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        tonalElevation = 4.dp,
                        shadowElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(action.labelRes),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(animatedCornerRadius),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                onClick = { updateExpanded(!isExpanded) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.add)
                    )
                }
            }
        }
    }
}

private enum class AutofillAddTarget {
    PASSWORD,
    DOCUMENT,
    BANK_CARD,
    BILLING_ADDRESS
}

private fun resolveAutofillAddTargets(
    requestProfile: AutofillPickerRequestProfile,
    loginHintCount: Int,
    bankCardHintCount: Int,
    documentHintCount: Int,
    billingAddressHintCount: Int,
): List<AutofillAddTarget> {
    val requestedTargets = buildList {
        if (requestProfile.wantsPasswords) add(AutofillAddTarget.PASSWORD to loginHintCount)
        if (requestProfile.wantsDocuments) add(AutofillAddTarget.DOCUMENT to documentHintCount)
        if (requestProfile.wantsBankCards) add(AutofillAddTarget.BANK_CARD to bankCardHintCount)
        if (requestProfile.wantsBillingAddresses) add(AutofillAddTarget.BILLING_ADDRESS to billingAddressHintCount)
    }
    if (requestedTargets.isEmpty()) return emptyList()

    fun priorityOf(target: AutofillAddTarget): Int = when (target) {
        AutofillAddTarget.PASSWORD -> 3
        AutofillAddTarget.DOCUMENT -> 2
        AutofillAddTarget.BANK_CARD -> 1
        AutofillAddTarget.BILLING_ADDRESS -> 2
    }

    return requestedTargets.sortedWith(
        compareBy<Pair<AutofillAddTarget, Int>> { it.second }
            .thenBy { priorityOf(it.first) }
    ).map { it.first }
}

private const val KEEPASS_GROUP_UNCATEGORIZED = "__keepass_uncategorized__"
private const val BITWARDEN_FOLDER_UNCATEGORIZED = "__bitwarden_uncategorized__"

private fun SecureItem.matchesAutofillSourceFilter(
    sourceFilter: AutofillStorageSourceFilter,
    selectedKeePassDatabaseId: Long?,
    selectedKeePassGroupPath: String?,
    selectedVaultId: Long?,
    selectedFolderId: String?,
): Boolean {
    return when (sourceFilter) {
        AutofillStorageSourceFilter.ALL -> true
        AutofillStorageSourceFilter.LOCAL ->
            isLocalPasswordOwnership(keepassDatabaseId, bitwardenVaultId, mdbxDatabaseId)
        AutofillStorageSourceFilter.KEEPASS -> {
            val keepassId = keepassDatabaseId
            keepassId != null &&
                (selectedKeePassDatabaseId == null || keepassId == selectedKeePassDatabaseId) &&
                when (selectedKeePassGroupPath) {
                    null -> true
                    KEEPASS_GROUP_UNCATEGORIZED -> keepassGroupPath.isNullOrBlank()
                    else -> keepassGroupPath == selectedKeePassGroupPath
                }
        }
        AutofillStorageSourceFilter.BITWARDEN -> {
            val vaultId = bitwardenVaultId
            vaultId != null &&
                (selectedVaultId == null || vaultId == selectedVaultId) &&
                when (selectedFolderId) {
                    null -> true
                    BITWARDEN_FOLDER_UNCATEGORIZED -> bitwardenFolderId.isNullOrBlank()
                    else -> bitwardenFolderId == selectedFolderId
                }
        }
    }
}

private fun BillingAddressData.toAutofillAddressForPicker(): String {
    return listOf(
        streetAddress,
        apartment,
        city,
        stateProvince,
        postalCode,
        country,
    ).filter { it.isNotBlank() }.joinToString(", ")
}

private fun AutofillStorageSourceFilter.labelResId(): Int = when (this) {
    AutofillStorageSourceFilter.ALL -> R.string.filter_all
    AutofillStorageSourceFilter.LOCAL -> R.string.filter_monica
    AutofillStorageSourceFilter.KEEPASS -> R.string.filter_keepass
    AutofillStorageSourceFilter.BITWARDEN -> R.string.filter_bitwarden
}

private fun AutofillStorageSourceFilter.icon(): ImageVector = when (this) {
    AutofillStorageSourceFilter.ALL -> Icons.Default.FilterList
    AutofillStorageSourceFilter.LOCAL -> Icons.Default.Smartphone
    AutofillStorageSourceFilter.KEEPASS -> Icons.Default.Storage
    AutofillStorageSourceFilter.BITWARDEN -> Icons.Default.CloudSync
}

private fun PasswordEntry.matchesSearchQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) ||
        appName.contains(query, ignoreCase = true) ||
        username.contains(query, ignoreCase = true) ||
        website.contains(query, ignoreCase = true)
}

@Composable
private fun NoSuggestionsHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.autofill_no_suggestions_in_context),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AutofillExpressiveSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    showMarkButton: Boolean,
    onMarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_passwords),
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_search)
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
        if (showMarkButton) {
            FilledTonalIconButton(
                onClick = onMarkClick,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = stringResource(R.string.autofill_mark_not_field_button)
                )
            }
        }
    }
}

@Composable
private fun AutofillFilterTrigger(
    sourceFilter: AutofillStorageSourceFilter,
    summary: String,
    chips: List<String>,
    activeFilterCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    panelContent: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val triggerShape = RoundedCornerShape(22.dp)
    val panelShape = RoundedCornerShape(20.dp)
    val containerMotion = tween<IntSize>(
        durationMillis = 140,
        easing = LinearEasing
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "filter_arrow_rotation"
    )
    val visibleChips = if (activeFilterCount > 0) chips.take(2) else emptyList()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = containerMotion),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier
                .clip(triggerShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = triggerShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (expanded) 2.dp else 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    ) {
                        Icon(
                            imageVector = sourceFilter.icon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)
                                .padding(6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.filter_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (activeFilterCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = activeFilterCount.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(5.dp)
                                .graphicsLayer { rotationZ = arrowRotation }
                        )
                    }
                }

                if (visibleChips.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        visibleChips.forEach { chip ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = chip,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                        if (chips.size > visibleChips.size) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "+${chips.size - visibleChips.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (panelContent != null) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 110, easing = LinearEasing)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 130, easing = LinearEasing),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 90, easing = LinearEasing))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = containerMotion),
                    shape = panelShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        panelContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterScopeCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutofillFilterPanelBody(
    sourceFilter: AutofillStorageSourceFilter,
    activeFilterCount: Int,
    keepassDatabases: List<LocalKeePassDatabase>,
    keepassNameById: Map<Long, String>,
    selectedKeePassDatabaseId: Long?,
    selectedKeePassGroupPath: String?,
    keepassGroupsForSelectedDb: List<String>,
    hasUncategorizedKeepassEntries: Boolean,
    bitwardenVaults: List<BitwardenVault>,
    vaultLabelById: Map<Long, String>,
    selectedVaultId: Long?,
    selectedFolderId: String?,
    selectedVaultFolders: List<BitwardenFolder>,
    folderNameById: Map<String, String>,
    hasUncategorizedBitwardenEntries: Boolean,
    onSourceFilterChange: (AutofillStorageSourceFilter) -> Unit,
    onSelectKeePassDatabase: (Long?) -> Unit,
    onSelectKeePassGroup: (String?) -> Unit,
    onSelectVault: (Long?) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onResetAllFilters: () -> Unit
) {
    var keepassMenuExpanded by remember { mutableStateOf(false) }
    var keepassGroupMenuExpanded by remember { mutableStateOf(false) }
    var vaultMenuExpanded by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val sourceOptions = AutofillStorageSourceFilter.values().toList()
    val hasActiveFilters = activeFilterCount > 0
    val dropdownFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sourceOptions.forEach { option ->
                FilterChip(
                    selected = sourceFilter == option,
                    onClick = { onSourceFilterChange(option) },
                    label = { Text(stringResource(option.labelResId())) },
                    leadingIcon = {
                        Icon(
                            imageVector = option.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        if (sourceFilter == AutofillStorageSourceFilter.KEEPASS) {
            FilterScopeCard(title = stringResource(R.string.filter_keepass)) {
                Text(
                    text = stringResource(R.string.password_picker_filter_database),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = keepassMenuExpanded,
                    onExpandedChange = { keepassMenuExpanded = !keepassMenuExpanded }
                ) {
                    TextField(
                        readOnly = true,
                        value = selectedKeePassDatabaseId?.let { keepassNameById[it] }
                            ?: stringResource(R.string.password_picker_all_databases),
                        onValueChange = {},
                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepassMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = dropdownFieldColors
                    )
                    ExposedDropdownMenu(
                        expanded = keepassMenuExpanded,
                        onDismissRequest = { keepassMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_databases)) },
                            onClick = {
                                onSelectKeePassDatabase(null)
                                keepassMenuExpanded = false
                            }
                        )
                        keepassDatabases.forEach { databaseItem ->
                            DropdownMenuItem(
                                text = { Text(databaseItem.name) },
                                onClick = {
                                    onSelectKeePassDatabase(databaseItem.id)
                                    keepassMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedKeePassDatabaseId != null) {
                    Text(
                        text = stringResource(R.string.password_picker_filter_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExposedDropdownMenuBox(
                        expanded = keepassGroupMenuExpanded,
                        onExpandedChange = { keepassGroupMenuExpanded = !keepassGroupMenuExpanded }
                    ) {
                        TextField(
                            readOnly = true,
                            value = when (selectedKeePassGroupPath) {
                                null -> stringResource(R.string.password_picker_all_folders)
                                KEEPASS_GROUP_UNCATEGORIZED -> stringResource(R.string.category_none)
                                else -> selectedKeePassGroupPath
                            },
                            onValueChange = {},
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepassGroupMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = dropdownFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = keepassGroupMenuExpanded,
                            onDismissRequest = { keepassGroupMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_folders)) },
                                onClick = {
                                    onSelectKeePassGroup(null)
                                    keepassGroupMenuExpanded = false
                                }
                            )
                            if (hasUncategorizedKeepassEntries) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_none)) },
                                    onClick = {
                                        onSelectKeePassGroup(KEEPASS_GROUP_UNCATEGORIZED)
                                        keepassGroupMenuExpanded = false
                                    }
                                )
                            }
                            keepassGroupsForSelectedDb.forEach { groupPath ->
                                DropdownMenuItem(
                                    text = { Text(groupPath) },
                                    onClick = {
                                        onSelectKeePassGroup(groupPath)
                                        keepassGroupMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sourceFilter == AutofillStorageSourceFilter.BITWARDEN) {
            FilterScopeCard(title = stringResource(R.string.filter_bitwarden)) {
                Text(
                    text = stringResource(R.string.password_picker_filter_vault),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = vaultMenuExpanded,
                    onExpandedChange = { vaultMenuExpanded = !vaultMenuExpanded }
                ) {
                    TextField(
                        readOnly = true,
                        value = selectedVaultId?.let { vaultLabelById[it] }
                            ?: stringResource(R.string.password_picker_all_vaults),
                        onValueChange = {},
                        leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vaultMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = dropdownFieldColors
                    )
                    ExposedDropdownMenu(
                        expanded = vaultMenuExpanded,
                        onDismissRequest = { vaultMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_vaults)) },
                            onClick = {
                                onSelectVault(null)
                                vaultMenuExpanded = false
                            }
                        )
                        bitwardenVaults.forEach { vault ->
                            val label = vaultLabelById[vault.id].orEmpty()
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onSelectVault(vault.id)
                                    vaultMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedVaultId != null) {
                    Text(
                        text = stringResource(R.string.password_picker_filter_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExposedDropdownMenuBox(
                        expanded = folderMenuExpanded,
                        onExpandedChange = { folderMenuExpanded = !folderMenuExpanded }
                    ) {
                        TextField(
                            readOnly = true,
                            value = when (selectedFolderId) {
                                null -> stringResource(R.string.password_picker_all_folders)
                                BITWARDEN_FOLDER_UNCATEGORIZED -> stringResource(R.string.category_none)
                                else -> folderNameById[selectedFolderId].orEmpty()
                            },
                            onValueChange = {},
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = dropdownFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_folders)) },
                                onClick = {
                                    onSelectFolder(null)
                                    folderMenuExpanded = false
                                }
                            )
                            if (hasUncategorizedBitwardenEntries) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_none)) },
                                    onClick = {
                                        onSelectFolder(BITWARDEN_FOLDER_UNCATEGORIZED)
                                        folderMenuExpanded = false
                                    }
                                )
                            }
                            selectedVaultFolders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        onSelectFolder(folder.bitwardenFolderId)
                                        folderMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hasActiveFilters) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onResetAllFilters) {
                    Text(text = stringResource(R.string.clear))
                }
            }
        }
    }
}




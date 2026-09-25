package takagi.ru.monica.ime

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import java.util.Locale
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.isLocalPasswordOwnership
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.ImePasswordRow
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.AutofillSecretResolver
import takagi.ru.monica.autofill_ng.AutofillPickerActivityV2
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.SettingsManager

open class MonicaInputMethodService : InputMethodService() {

    companion object {
        const val ACTION_IME_BIOMETRIC_RESULT = "takagi.ru.monica.ime.action.BIOMETRIC_RESULT"
        const val EXTRA_IME_BIOMETRIC_SUCCESS = "extra_ime_biometric_success"
        const val EXTRA_IME_BIOMETRIC_ERROR = "extra_ime_biometric_error"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleOwner = ServiceComposeViewLifecycleOwner()
    private val uiState = MutableStateFlow(MonicaImeUiState())

    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var autofillPreferences: AutofillPreferences
    private lateinit var database: PasswordDatabase
    private var composeView: ComposeView? = null
    private var recomposer: Recomposer? = null
    private var refreshJob: Job? = null
    private var refreshRequest: ImeRefreshRequest? = null
    private var databaseObserverJob: Job? = null
    private var authenticatorTickerJob: Job? = null
    private var vaultSourceCache: ImeVaultSourceCache? = null
    private var vaultGeneration = 0L
    private var totpSourceCache: List<SecureItem>? = null
    private var cardWalletSourceCache: List<SecureItem>? = null
    private val loadedVaultPanels = mutableMapOf<MonicaImePanel, ImeVaultPresentation>()
    private var pendingUnlockPanel: MonicaImePanel? = null
    private var pendingClearedInputText: String? = null
    private var unlockFlowInProgress = false
    private var suppressAutoUnlockUntilNextAttempt = false
    private var inputViewVisible = false
    private val pinRandom by lazy { java.security.SecureRandom() }
    private var editorIdentity: Pair<String, Int>? = null
    private var inputGeneration = 0L
    private val imeUnlockResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_IME_BIOMETRIC_RESULT) {
                handleImeBiometricResult(intent)
            }
        }
    }

    private fun handleImeBiometricResult(intent: Intent) {
        val success = intent.getBooleanExtra(EXTRA_IME_BIOMETRIC_SUCCESS, false)
        val errorMessage = intent.getStringExtra(EXTRA_IME_BIOMETRIC_ERROR)
        val targetPanel = pendingUnlockPanel ?: MonicaImePanel.PASSWORDS
        unlockFlowInProgress = false
        pendingUnlockPanel = null

        if (success) {
            suppressAutoUnlockUntilNextAttempt = false
            uiState.update {
                it.copy(
                    unlocked = true,
                    activePanel = targetPanel,
                    isAutofillPanelVisible = targetPanel != MonicaImePanel.KEYBOARD,
                    isAutofillLoading = targetPanel.requiresInitialVaultLoading(),
                    errorMessage = null
                )
            }
            requestRefreshVaultEntries(force = true)
        } else {
            suppressAutoUnlockUntilNextAttempt = true
            uiState.update {
                it.copy(
                    unlocked = false,
                    activePanel = targetPanel,
                    isAutofillPanelVisible = targetPanel != MonicaImePanel.KEYBOARD,
                    isAutofillLoading = false,
                    errorMessage = errorMessage ?: strings.get(takagi.ru.monica.R.string.ime_unlock_required)
                )
            }
        }
    }

    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.onCreate()
        securityManager = SecurityManager(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        autofillPreferences = AutofillPreferences(applicationContext)
        database = PasswordDatabase.getDatabase(applicationContext)
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(
            imeUnlockResultReceiver,
            IntentFilter().apply {
                addAction(ACTION_IME_BIOMETRIC_RESULT)
            },
            receiverFlags
        )
        observeDatabaseSources()
        observeAuthenticatorTicker()

        serviceScope.launch {
            autofillPreferences.imeKeyboardOptions.distinctUntilChanged().collect { options ->
                uiState.update { state -> state.copy(keyboardOptions = options,
                    pinDigits = if (state.keyboardOptions.scramblePin != options.scramblePin)
                        createImePinDigits(options.scramblePin, pinRandom) else state.pinDigits) }
            }
        }

        observeVaultLock()

        serviceScope.launch {
            val settings = settingsManager.settingsFlow.first()
            uiState.update { it.copy(autoLockMinutes = settings.autoLockMinutes) }
            refreshVaultEntries()
        }
    }

    private fun observeVaultLock() {
        serviceScope.launch {
            combine(SessionManager.isUnlocked, takagi.ru.monica.security.SecondarySessionManager.isUnlocked) {
                main, secondary -> main || secondary
            }.distinctUntilChanged().collect { unlocked ->
                if (!unlocked) {
                    refreshJob?.cancel()
                    invalidateVaultSourceCache()
                    uiState.update { it.copy(unlocked = false, entries = emptyList(),
                        authenticatorEntries = emptyList(), cardWalletEntries = emptyList(),
                        databaseOptions = emptyList(), isAutofillLoading = false, isSearchEditing = false,
                        query = "", pendingClearedInput = null) }
                    pendingClearedInputText = null
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        if (composeView == null) {
            recomposer = Recomposer(AndroidUiDispatcher.Main).also { createdRecomposer ->
                serviceScope.launch(AndroidUiDispatcher.Main) {
                    createdRecomposer.runRecomposeAndApplyChanges()
                }
            }
            composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setParentCompositionContext(recomposer)
                setContent {
                    val settings = settingsManager.settingsFlow.collectAsState(
                        initial = AppSettings(language = StartupLanguageCache.read(this@MonicaInputMethodService))
                    ).value
                    val state = uiState.collectAsState().value
                    val language = StartupLanguageCache.languageFlow(this@MonicaInputMethodService)
                        .collectAsState().value

                    val localizedContext = remember(language, LocalConfiguration.current) {
                        LocaleHelper.setLocale(this@MonicaInputMethodService, language)
                    }
                    CompositionLocalProvider(
                        LocalContext provides localizedContext,
                        LocalConfiguration provides localizedContext.resources.configuration
                    ) {
                        MonicaImeContent(
                            settings = settings,
                            uiState = state,
                            onDatabaseScopeSelected = { scope ->
                                uiState.update { it.copy(selectedDatabaseScope = scope) }
                                requestRefreshVaultEntries()
                            },
                            onInsertPassword = { entry ->
                                resolveFillableField(entry.password)?.let(::commitExternalText)
                            },
                            onInsertUsername = { entry ->
                                resolveFillableField(entry.username)?.let(::commitExternalText)
                            },
                            onInsertWebsite = { entry ->
                                resolveFillableField(entry.website)?.let(::commitExternalText)
                            },
                            onSmartFillPassword = ::handleSmartFillPassword,
                            onInsertPasswordTotp = ::insertCurrentPasswordTotp,
                            onInsertAuthenticatorCode = { commitExternalText(it.code) },
                            onInsertCardWalletValue = { commitExternalText(it.value) },
                            onSmartFillCardWallet = ::handleSmartFillCardWallet,
                            onKeyPressed = ::handleKeyPress,
                            onBackspace = ::handleBackspace,
                            onDeleteAll = ::handleDeleteAll,
                            onUndoDeleteAll = ::handleUndoDeleteAll,
                            onEnter = ::handleEnter,
                            onSpace = { handleKeyPress(" ") },
                            onShiftToggle = {
                                uiState.update { it.copy(isUppercase = !it.isUppercase) }
                            },
                            onKeyboardModeChange = { mode ->
                                uiState.update { state -> state.copy(keyboardMode = mode,
                                    pinDigits = if (mode == MonicaKeyboardMode.NUMBERS && state.keyboardMode != mode)
                                        createImePinDigits(state.keyboardOptions.scramblePin, pinRandom) else state.pinDigits) }
                            },
                            onOpenUnlockApp = ::openMonicaAppForUnlock,
                            onOpenAutofillSettings = ::openAutofillPickerPage,
                            onSearchEditRequested = ::startImeSearchEditing,
                            onSearchEditFinished = ::finishImeSearchEditing,
                            onSearchCleared = ::clearImeSearchQuery,
                            onSwitchInputMethod = ::switchToNextInputMethod,
                            onPanelSelected = ::handlePanelSelection,
                            onDismiss = { requestHideSelf(0) }
                        )
                    }
                }
            }
        }
        return composeView!!
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputGeneration++
        inputViewVisible = true
        window?.window?.let { imeWindow ->
            imeWindow.navigationBarColor = Color.BLACK
            imeWindow.decorView.setBackgroundColor(Color.BLACK)
        }
        val previousState = uiState.value
        val identity = (info?.packageName.orEmpty() to (info?.fieldId ?: 0))
        val newSession = !restarting || identity != editorIdentity
        editorIdentity = identity
        val incomingPackageName = info?.packageName?.takeIf { it.isNotBlank() }
        val effectivePackageName = incomingPackageName ?: previousState.activePackageName
        val packageUnchanged =
            incomingPackageName == null || previousState.activePackageName == incomingPackageName
        val preserveAutofillPanel =
            previousState.activePanel != MonicaImePanel.KEYBOARD && packageUnchanged
        val shouldRefreshForCurrentView = previousState.unlocked ||
            !preserveAutofillPanel ||
            previousState.entries.isNotEmpty() ||
            previousState.authenticatorEntries.isNotEmpty() ||
            previousState.cardWalletEntries.isNotEmpty()
        uiState.update {
            it.copy(
                activePackageName = effectivePackageName,
                keyboardMode = if (newSession) imeModeForInputType(info?.inputType ?: 0) else it.keyboardMode,
                isSensitiveInput = imeInputIsSensitive(info?.inputType ?: 0),
                pinDigits = if (newSession) createImePinDigits(it.keyboardOptions.scramblePin, pinRandom) else it.pinDigits,
                activePanel = if (preserveAutofillPanel) previousState.activePanel else MonicaImePanel.KEYBOARD,
                isAutofillPanelVisible = preserveAutofillPanel,
                isAutofillLoading = preserveAutofillPanel &&
                    previousState.activePanel.requiresInitialVaultLoading() &&
                    shouldRefreshForCurrentView,
                isSearchEditing = false,
                query = if (preserveAutofillPanel) previousState.query else "",
                passwordSortMode = if (preserveAutofillPanel) {
                    previousState.passwordSortMode
                } else {
                    MonicaImePasswordSortMode.ALPHABETICAL
                },
                selectedDatabaseScope = if (preserveAutofillPanel) {
                    previousState.selectedDatabaseScope
                } else {
                    MonicaImeDatabaseScope.All
                },
                errorMessage = null
            )
        }
        if (shouldRefreshForCurrentView) {
            requestRefreshVaultEntries()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        inputGeneration++
        inputViewVisible = false
        refreshJob?.cancel()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        inputViewVisible = true
        requestRefreshVaultEntries()
    }

    override fun onWindowHidden() {
        inputViewVisible = false
        refreshJob?.cancel()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        authenticatorTickerJob?.cancel()
        databaseObserverJob?.cancel()
        refreshJob?.cancel()
        invalidateVaultSourceCache()
        unregisterReceiver(imeUnlockResultReceiver)
        composeView?.disposeComposition()
        composeView = null
        recomposer?.cancel()
        recomposer = null
        serviceScope.cancel()
        lifecycleOwner.onDestroy()
        super.onDestroy()
    }

    private fun openMonicaAppForUnlock() {
        val targetPanel = pendingUnlockPanel
            ?: uiState.value.activePanel.takeIf { it != MonicaImePanel.KEYBOARD }
            ?: MonicaImePanel.PASSWORDS

        pendingUnlockPanel = targetPanel
        suppressAutoUnlockUntilNextAttempt = false

        if (unlockFlowInProgress) {
            return
        }

        unlockFlowInProgress = true
        runCatching {
            startActivity(
                Intent(this, ImeUnlockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
        }.onFailure { error ->
            unlockFlowInProgress = false
            uiState.update {
                it.copy(errorMessage = error.message ?: strings.get(takagi.ru.monica.R.string.ime_unlock_open_app_error))
            }
        }
    }

    private fun openAutofillPickerPage() {
        clearPendingDeleteUndo()
        val activePackageName = uiState.value.activePackageName.takeIf { it.isNotBlank() }
        uiState.update { it.copy(isSearchEditing = false) }
        runCatching {
            val pickerArgs = AutofillPickerActivityV2.Args(
                applicationId = activePackageName,
                isSaveMode = false,
                rememberLastFilled = false
            )
            startActivity(
                AutofillPickerActivityV2.getIntent(this, pickerArgs).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
                    putExtra(AutofillPickerActivityV2.EXTRA_IME_MODE, true)
                }
            )
        }.onSuccess {
            requestHideSelf(0)
        }.onFailure { error ->
            uiState.update {
                it.copy(
                    errorMessage = error.message
                        ?: strings.get(takagi.ru.monica.R.string.ime_unlock_open_app_error)
                )
            }
        }
    }

    private fun requestRefreshVaultEntries(
        force: Boolean = false,
        debounceMillis: Long = 0L
    ) {
        if (force) invalidateVaultSourceCache()
        val currentState = uiState.value
        val request = ImeRefreshRequest(currentState.activePanel, currentState.vaultPresentation(), vaultGeneration)
        if (refreshJob?.isActive == true && refreshRequest == request) return
        refreshJob?.cancel()
        refreshRequest = request
        if (
            currentState.unlocked &&
            currentState.activePanel.requiresInitialVaultLoading() &&
            currentState.isAutofillPanelVisible
        ) {
            uiState.update { it.copy(isAutofillLoading = true) }
        }
        refreshJob = serviceScope.launch {
            try {
                if (debounceMillis > 0L) {
                    delay(debounceMillis)
                }
                refreshVaultEntries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MonicaIME", "Failed to refresh IME autofill entries", e)
                uiState.update {
                    it.copy(
                        isAutofillLoading = false,
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    private fun observeDatabaseSources() {
        databaseObserverJob?.cancel()
        databaseObserverJob = serviceScope.launch {
            combine(
                combine(
                    database.localKeePassDatabaseDao().getAllDatabases()
                    .map { databases ->
                        databases.map { database ->
                            Triple(database.id, database.name, database.filePath)
                        }
                    }
                    .distinctUntilChanged(),
                    database.bitwardenVaultDao().getAllVaultsFlow()
                    .map { vaults ->
                        vaults.map { vault ->
                            Triple(vault.id, vault.email, vault.displayName.orEmpty())
                        }
                    }
                    .distinctUntilChanged(),
                    database.localMdbxDatabaseDao().getAvailableDatabases()
                        .map { databases -> databases.map { Triple(it.id, it.name, it.engineType) } }
                        .distinctUntilChanged()
                ) { keepassSignatures, bitwardenSignatures, mdbxSignatures ->
                    Triple(keepassSignatures, bitwardenSignatures, mdbxSignatures)
                },
                database.passwordEntryDao().observeActivePasswordRevision(),
                database.secureItemDao().observeActiveItemRevision()
            ) { sourceSignatures, passwordRevision, secureItemRevision ->
                Triple(sourceSignatures, passwordRevision, secureItemRevision)
            }.collect {
                invalidateVaultSourceCache()
                val currentState = uiState.value
                if (
                    inputViewVisible &&
                    currentState.activePanel != MonicaImePanel.KEYBOARD &&
                    currentState.isAutofillPanelVisible
                ) {
                    requestRefreshVaultEntries()
                }
            }
        }
    }

    private fun observeAuthenticatorTicker() {
        authenticatorTickerJob?.cancel()
        authenticatorTickerJob = serviceScope.launch {
            while (true) {
                delay(1_000)
                val currentState = uiState.value
                if (
                    inputViewVisible &&
                    currentState.unlocked &&
                    currentState.activePanel == MonicaImePanel.AUTHENTICATORS &&
                    currentState.isAutofillPanelVisible
                ) {
                    requestRefreshVaultEntries()
                }
            }
        }
    }

    private fun handlePanelSelection(panel: MonicaImePanel) {
        clearPendingDeleteUndo()
        if (panel == MonicaImePanel.KEYBOARD) {
            suppressAutoUnlockUntilNextAttempt = false
            uiState.update {
                it.copy(
                    activePanel = MonicaImePanel.KEYBOARD,
                    isAutofillPanelVisible = false,
                    isAutofillLoading = false,
                    isSearchEditing = false,
                    query = "",
                    passwordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
                    selectedDatabaseScope = MonicaImeDatabaseScope.All,
                    errorMessage = null
                )
            }
            return
        }
        if (panel == MonicaImePanel.GENERATOR) {
            suppressAutoUnlockUntilNextAttempt = false
            uiState.update {
                it.copy(
                    activePanel = MonicaImePanel.GENERATOR,
                    isAutofillPanelVisible = true,
                    isAutofillLoading = false,
                    isSearchEditing = false,
                    query = "",
                    passwordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
                    selectedDatabaseScope = MonicaImeDatabaseScope.All,
                    errorMessage = null
                )
            }
            return
        }

        serviceScope.launch {
            val settings = settingsManager.settingsFlow.first()
            val unlockedNow = updateUnlockState(settings)
            if (!unlockedNow) {
                pendingUnlockPanel = panel
                suppressAutoUnlockUntilNextAttempt = false
                uiState.update {
                    it.copy(
                        unlocked = false,
                        activePanel = panel,
                        isAutofillPanelVisible = true,
                        entries = emptyList(),
                        authenticatorEntries = emptyList(),
                        cardWalletEntries = emptyList(),
                        databaseOptions = emptyList(),
                        isAutofillLoading = false,
                        isSearchEditing = false,
                        query = "",
                        passwordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
                        selectedDatabaseScope = MonicaImeDatabaseScope.All,
                        errorMessage = strings.get(takagi.ru.monica.R.string.ime_unlock_required)
                    )
                }
                openMonicaAppForUnlock()
                return@launch
            }

            val previousState = uiState.value
            val requiresInitialLoad = panel.requiresInitialVaultLoading()
            val nextState = previousState.selectVaultPanel(panel, isLoading = requiresInitialLoad)
            val needsPresentationRefresh = loadedVaultPanels[panel] != nextState.vaultPresentation()
            uiState.value = nextState
            if (requiresInitialLoad || needsPresentationRefresh) {
                requestRefreshVaultEntries()
            }
        }
    }

    private suspend fun refreshVaultEntries(force: Boolean = false) {
        val settings = settingsManager.settingsFlow.first()
        val isUnlocked = updateUnlockState(settings)
        if (!isUnlocked) {
            val currentState = uiState.value
            uiState.update {
                it.copy(
                    entries = emptyList(),
                    authenticatorEntries = emptyList(),
                    cardWalletEntries = emptyList(),
                    autoLockMinutes = settings.autoLockMinutes,
                    isAutofillLoading = false
                )
            }
            return
        }

        val currentState = uiState.value
        if (
            currentState.activePanel == MonicaImePanel.KEYBOARD ||
            currentState.activePanel == MonicaImePanel.GENERATOR ||
            !currentState.isAutofillPanelVisible
        ) {
            uiState.update {
                it.copy(
                    autoLockMinutes = settings.autoLockMinutes,
                    isAutofillLoading = false,
                    errorMessage = null
                )
            }
            return
        }
        val activePackage = currentState.activePackageName
        val query = currentState.query.trim()
        val localLabel = strings.get(takagi.ru.monica.R.string.filter_monica)
        val keepassLabel = strings.get(takagi.ru.monica.R.string.filter_keepass)
        val mdbxLabel = "MDBX"
        val bitwardenLabel = strings.get(takagi.ru.monica.R.string.filter_bitwarden)
        val allDatabasesLabel = strings.get(takagi.ru.monica.R.string.password_picker_all_databases)
        if (force) invalidateVaultSourceCache()
        val generation = vaultGeneration
        val cachedSources = vaultSourceCache
        val cachedTotp = totpSourceCache
        val cachedCards = cardWalletSourceCache
        val snapshot = withContext(Dispatchers.IO) {
            val sources = cachedSources ?: loadImeVaultSources(
                    localLabel = localLabel,
                    keepassLabel = keepassLabel,
                    mdbxLabel = mdbxLabel,
                    bitwardenLabel = bitwardenLabel,
                    allDatabasesLabel = allDatabasesLabel
                )
            val selectedScope = currentState.selectedDatabaseScope
                .takeIf { scope -> sources.databaseOptions.any { it.scope == scope } }
                ?: MonicaImeDatabaseScope.All

            val results = if (currentState.activePanel == MonicaImePanel.PASSWORDS) {
                sources.passwordIndex.query(
                    rawQuery = query,
                    scope = selectedScope,
                    sortMode = currentState.passwordSortMode,
                    activePackageName = activePackage
                ).map(::ImeRefreshResult)
            } else {
                currentState.entries.map(::ImeRefreshResult)
            }

            val totpItems = if (currentState.activePanel == MonicaImePanel.AUTHENTICATORS) {
                cachedTotp ?: database.secureItemDao()
                    .getActiveItemsByTypeSync(ItemType.TOTP)
            } else cachedTotp
            val authenticatorResults = if (currentState.activePanel == MonicaImePanel.AUTHENTICATORS) {
                buildAuthenticatorEntries(
                    secureItems = totpItems.orEmpty(),
                    passwordEntries = sources.passwordEntries,
                    keepassLookup = sources.keepassLookup,
                    mdbxLookup = sources.mdbxLookup,
                    bitwardenLookup = sources.bitwardenLookup,
                    localLabel = localLabel,
                    keepassLabel = keepassLabel,
                    mdbxLabel = mdbxLabel,
                    bitwardenLabel = bitwardenLabel,
                    query = query,
                    selectedScope = selectedScope
                )
            } else {
                currentState.authenticatorEntries
            }

            val cardWalletItems = if (currentState.activePanel == MonicaImePanel.DOCUMENTS) {
                cachedCards ?: (
                    database.secureItemDao().getActiveItemsByTypeSync(ItemType.BANK_CARD) +
                        database.secureItemDao().getActiveItemsByTypeSync(ItemType.DOCUMENT)
                    )
            } else cachedCards
            val cardWalletResults = if (currentState.activePanel == MonicaImePanel.DOCUMENTS) {
                buildCardWalletEntries(
                    secureItems = cardWalletItems.orEmpty(),
                    keepassLookup = sources.keepassLookup,
                    mdbxLookup = sources.mdbxLookup,
                    bitwardenLookup = sources.bitwardenLookup,
                    localLabel = localLabel,
                    keepassLabel = keepassLabel,
                    mdbxLabel = mdbxLabel,
                    bitwardenLabel = bitwardenLabel,
                    query = query,
                    selectedScope = selectedScope
                )
            } else {
                currentState.cardWalletEntries
            }

            ImeRefreshSnapshot(
                sources = sources,
                totpSources = totpItems,
                cardSources = cardWalletItems,
                results = results,
                authenticatorResults = authenticatorResults,
                cardWalletResults = cardWalletResults,
                databaseOptions = sources.databaseOptions,
                selectedScope = selectedScope
            )
        }

        currentCoroutineContext().ensureActive()
        if (generation != vaultGeneration || currentState.activePanel != uiState.value.activePanel ||
            currentState.vaultPresentation() != uiState.value.vaultPresentation()) return
        // Publish only on the service thread; a cancelled/locked refresh cannot resurrect old data.
        vaultSourceCache = snapshot.sources
        totpSourceCache = snapshot.totpSources
        cardWalletSourceCache = snapshot.cardSources
        val entries = snapshot.results.map { it.value }

        loadedVaultPanels[currentState.activePanel] = currentState
            .copy(selectedDatabaseScope = snapshot.selectedScope)
            .vaultPresentation()
        uiState.update {
            it.copy(
                unlocked = true,
                entries = entries,
                authenticatorEntries = snapshot.authenticatorResults,
                cardWalletEntries = snapshot.cardWalletResults,
                databaseOptions = snapshot.databaseOptions,
                selectedDatabaseScope = snapshot.selectedScope,
                autoLockMinutes = settings.autoLockMinutes,
                isAutofillLoading = false,
                errorMessage = null
            )
        }
    }

    private suspend fun loadImeVaultSources(
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String,
        allDatabasesLabel: String
    ): ImeVaultSourceCache {
            val keepassDatabases = database.localKeePassDatabaseDao().getAllDatabasesSync()
            val mdbxDatabases = database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()
            val bitwardenVaults = database.bitwardenVaultDao().getAllVaults()
            val keepassLookup = keepassDatabases.associateBy { it.id }
            val mdbxLookup = mdbxDatabases.associateBy { it.id }
            val bitwardenLookup = bitwardenVaults.associateBy { it.id }
            val databaseOptions = buildDatabaseOptions(
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel,
                allDatabasesLabel = allDatabasesLabel,
                keepassDatabases = keepassDatabases,
                mdbxDatabases = mdbxDatabases,
                bitwardenVaults = bitwardenVaults
            )
            val passwordEntries = database.passwordEntryDao()
                .getImePasswordRows()
            val loadContext = currentCoroutineContext()
            val passwordResults = passwordEntries
                .mapIndexedNotNull { index, entry ->
                    if (index % 64 == 0) loadContext.ensureActive()
                    entry.toImeEntryOrNull(
                        keepassLookup = keepassLookup,
                        mdbxLookup = mdbxLookup,
                        bitwardenLookup = bitwardenLookup,
                        localLabel = localLabel,
                        keepassLabel = keepassLabel,
                        mdbxLabel = mdbxLabel,
                        bitwardenLabel = bitwardenLabel
                    )
                }
        return ImeVaultSourceCache(
            passwordEntries = passwordEntries,
            passwordResults = passwordResults,
            passwordIndex = ImePasswordIndex(passwordResults.map(ImeRefreshResult::value)),
            keepassLookup = keepassLookup,
            mdbxLookup = mdbxLookup,
            bitwardenLookup = bitwardenLookup,
            databaseOptions = databaseOptions,
        )
    }

    private fun updateUnlockState(settings: AppSettings): Boolean {
        val unlocked = if (DeveloperVerificationPolicy.bypassesIdentityVerification(settings)) {
            securityManager.unlockVaultForDeveloperBypass(settings.autoLockMinutes)
        } else {
            securityManager.canAccessVaultNowStrict(this, settings.autoLockMinutes)
        }
        if (unlocked) {
            uiState.update { it.copy(unlocked = true, errorMessage = null) }
        } else {
            invalidateVaultSourceCache()
            uiState.update {
                val panelStillVisible = it.activePanel != MonicaImePanel.KEYBOARD && it.isAutofillPanelVisible
                it.copy(
                    unlocked = false,
                    entries = emptyList(),
                    authenticatorEntries = emptyList(),
                    cardWalletEntries = emptyList(),
                    databaseOptions = emptyList(),
                    isAutofillLoading = false,
                    isSearchEditing = false,
                    passwordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
                    selectedDatabaseScope = MonicaImeDatabaseScope.All,
                    errorMessage = if (panelStillVisible) {
                        strings.get(takagi.ru.monica.R.string.ime_unlock_required)
                    } else {
                        null
                    }
                )
            }
        }
        return unlocked
    }

    private fun entryMatchesScope(
        entry: MonicaImePasswordEntry,
        scope: MonicaImeDatabaseScope
    ): Boolean {
        return when (scope) {
            MonicaImeDatabaseScope.All -> true
            MonicaImeDatabaseScope.Local -> isLocalPasswordOwnership(
                entry.keepassDatabaseId,
                entry.bitwardenVaultId,
                entry.mdbxDatabaseId
            )
            is MonicaImeDatabaseScope.KeePass -> entry.keepassDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Mdbx -> entry.mdbxDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Bitwarden -> entry.bitwardenVaultId == scope.vaultId
        }
    }

    private fun entryMatchesScope(
        entry: MonicaImeAuthenticatorEntry,
        scope: MonicaImeDatabaseScope
    ): Boolean {
        return when (scope) {
            MonicaImeDatabaseScope.All -> true
            MonicaImeDatabaseScope.Local -> isLocalPasswordOwnership(
                entry.keepassDatabaseId,
                entry.bitwardenVaultId,
                entry.mdbxDatabaseId
            )
            is MonicaImeDatabaseScope.KeePass -> entry.keepassDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Mdbx -> entry.mdbxDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Bitwarden -> entry.bitwardenVaultId == scope.vaultId
        }
    }

    private fun entryMatchesScope(
        entry: MonicaImeCardWalletEntry,
        scope: MonicaImeDatabaseScope
    ): Boolean {
        return when (scope) {
            MonicaImeDatabaseScope.All -> true
            MonicaImeDatabaseScope.Local -> isLocalPasswordOwnership(
                entry.keepassDatabaseId,
                entry.bitwardenVaultId,
                entry.mdbxDatabaseId
            )
            is MonicaImeDatabaseScope.KeePass -> entry.keepassDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Mdbx -> entry.mdbxDatabaseId == scope.databaseId
            is MonicaImeDatabaseScope.Bitwarden -> entry.bitwardenVaultId == scope.vaultId
        }
    }

    private fun queryMatches(entry: MonicaImeAuthenticatorEntry, query: ImeSearchQuery): Boolean =
        query.matches(entry.title, entry.issuer, entry.accountName)

    private fun queryMatches(entry: MonicaImeCardWalletEntry, query: ImeSearchQuery): Boolean =
        query.matches(entry.title, entry.subtitle, entry.typeLabel,
            *entry.fields.flatMap { listOf(it.label, it.value) }.toTypedArray())

    private fun buildAuthenticatorEntries(
        secureItems: List<SecureItem>,
        passwordEntries: List<ImePasswordRow>,
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String,
        query: String,
        selectedScope: MonicaImeDatabaseScope
    ): List<MonicaImeAuthenticatorEntry> {
        val search = ImeSearchQuery(query)
        val storedEntries = secureItems.mapNotNull { item ->
            item.toImeAuthenticatorEntryOrNull(
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            )
        }
        val existingKeys = storedEntries.map { entry ->
            listOf(entry.issuer, entry.accountName, entry.title)
                .joinToString("|")
                .lowercase()
        }.toSet()
        val virtualEntries = passwordEntries.mapNotNull { password ->
            password.toVirtualImeAuthenticatorEntryOrNull(
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            )?.takeUnless { entry ->
                listOf(entry.issuer, entry.accountName, entry.title)
                    .joinToString("|")
                    .lowercase() in existingKeys
            }
        }

        val entries = (storedEntries + virtualEntries)
            .filter { entryMatchesScope(it, selectedScope) }
            .filter { queryMatches(it, search) }
        val sortKeys = entries.map { it.id }.zip(normalizedImeSortKeys(entries.map(::imeAuthenticatorAlphabeticalLabel))
            .map { it.lowercase(Locale.ROOT) }).toMap()
        return entries.map { it.copy(alphabeticalLetter = imeIndexLetter(sortKeys.getValue(it.id))) }.sortedWith(
                compareByDescending<MonicaImeAuthenticatorEntry> { it.isFavorite }
                    .thenBy {
                        sortKeys[it.id]
                    }
            )
    }

    private fun buildCardWalletEntries(
        secureItems: List<SecureItem>,
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String,
        query: String,
        selectedScope: MonicaImeDatabaseScope
    ): List<MonicaImeCardWalletEntry> {
        val search = ImeSearchQuery(query)
        val entries = secureItems
            .mapNotNull { item ->
                item.toImeCardWalletEntryOrNull(
                    keepassLookup = keepassLookup,
                    mdbxLookup = mdbxLookup,
                    bitwardenLookup = bitwardenLookup,
                    localLabel = localLabel,
                    keepassLabel = keepassLabel,
                    mdbxLabel = mdbxLabel,
                    bitwardenLabel = bitwardenLabel
                )
            }
            .filter { entryMatchesScope(it, selectedScope) }
            .filter { queryMatches(it, search) }
        val sortKeys = entries.map { it.id }.zip(normalizedImeSortKeys(entries.map(::imeCardWalletAlphabeticalLabel))
            .map { it.lowercase(Locale.ROOT) }).toMap()
        return entries.map { it.copy(alphabeticalLetter = imeIndexLetter(sortKeys.getValue(it.id))) }.sortedWith(
                compareByDescending<MonicaImeCardWalletEntry> { it.isFavorite }
                    .thenBy { it.typeLabel }
                    .thenBy {
                        sortKeys[it.id]
                    }
            )
    }

    private fun SecureItem.toImeAuthenticatorEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): MonicaImeAuthenticatorEntry? {
        if (itemType != ItemType.TOTP) return null
        val parsed = TotpDataResolver.parseStoredItemData(
            itemData = itemData,
            fallbackIssuer = title,
            fallbackAccountName = "",
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        ) ?: return null
        val resolved = parsed.resolveReadableTotpData() ?: return null
        val code = TotpGenerator.generateOtp(resolved)
        if (code.isBlank()) return null
        return MonicaImeAuthenticatorEntry(
            id = id,
            title = title.ifBlank {
                resolved.issuer.ifBlank {
                    resolved.accountName.ifBlank { strings.get(takagi.ru.monica.R.string.authenticator) }
                }
            },
            issuer = resolved.issuer,
            accountName = resolved.accountName,
            code = code,
            remainingSeconds = if (resolved.otpType == OtpType.HOTP) {
                0
            } else {
                TotpGenerator.getRemainingSeconds(resolved.period)
            },
            isFavorite = isFavorite,
            sourceLabel = resolveSourceLabel(
                item = this,
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            ),
            keepassDatabaseId = keepassDatabaseId,
            mdbxDatabaseId = mdbxDatabaseId,
            bitwardenVaultId = bitwardenVaultId
        )
    }

    private fun decryptStoredSensitiveValue(value: String): String {
        return runCatching {
            securityManager.decryptDataIfMonicaCiphertext(value)
        }.getOrDefault(value)
    }

    private fun insertCurrentPasswordTotp(entry: MonicaImePasswordEntry) {
        val target = inputGeneration
        val connection = currentInputConnection ?: return
        serviceScope.launch {
            val settings = settingsManager.settingsFlow.first()
            if (!updateUnlockState(settings)) return@launch
            val code = try {
                withContext(Dispatchers.IO) {
                    val current = database.passwordEntryDao().getImePasswordRowById(entry.id)
                        ?: return@withContext ""
                    val parsed = TotpDataResolver.fromAuthenticatorKey(
                        decryptStoredSensitiveValue(current.authenticatorKey), current.title,
                        resolveFillableField(current.username).orEmpty())
                    parsed?.resolveReadableTotpData()?.let(TotpGenerator::generateOtp).orEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MonicaIME", "Could not read current OTP: ${error.javaClass.simpleName}")
                ""
            }
            if (target != inputGeneration || !inputViewVisible || currentInputConnection !== connection ||
                !updateUnlockState(settings)) return@launch
            if (code.isNotBlank()) {
                clearPendingDeleteUndo()
                connection.commitText(code, 1)
            }
            else uiState.update { it.copy(errorMessage = strings.get(takagi.ru.monica.R.string.ime_otp_unavailable)) }
        }
    }

    private fun ImePasswordRow.toVirtualImeAuthenticatorEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): MonicaImeAuthenticatorEntry? {
        if (authenticatorKey.isBlank()) return null
        val resolvedUsername = resolveFillableField(username).orEmpty()
        val parsed = TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptStoredSensitiveValue(authenticatorKey),
            fallbackIssuer = title,
            fallbackAccountName = resolvedUsername
        ) ?: return null
        val resolved = parsed.resolveReadableTotpData() ?: return null
        val code = TotpGenerator.generateOtp(resolved)
        if (code.isBlank()) return null
        return MonicaImeAuthenticatorEntry(
            id = -id,
            title = title.ifBlank {
                resolved.issuer.ifBlank {
                    resolved.accountName.ifBlank { strings.get(takagi.ru.monica.R.string.authenticator) }
                }
            },
            issuer = resolved.issuer,
            accountName = resolved.accountName,
            code = code,
            remainingSeconds = if (resolved.otpType == OtpType.HOTP) {
                0
            } else {
                TotpGenerator.getRemainingSeconds(resolved.period)
            },
            isFavorite = isFavorite,
            sourceLabel = resolveSourceLabel(
                entry = this,
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            ),
            keepassDatabaseId = keepassDatabaseId,
            mdbxDatabaseId = mdbxDatabaseId,
            bitwardenVaultId = bitwardenVaultId
        )
    }

    private fun TotpData.resolveReadableTotpData(): TotpData? {
        val normalized = TotpDataResolver.normalizeTotpData(this)
        val readableSecret = resolveSecretValue(normalized.secret) ?: return null
        val readablePin = resolveSecretValue(normalized.pin) ?: normalized.pin
        return normalized.copy(secret = readableSecret, pin = readablePin)
    }

    private fun SecureItem.toImeCardWalletEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): MonicaImeCardWalletEntry? {
        return when (itemType) {
            ItemType.BANK_CARD -> toImeBankCardEntryOrNull(
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            )
            ItemType.DOCUMENT -> toImeDocumentEntryOrNull(
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            )
            else -> null
        }
    }

    private fun SecureItem.toImeBankCardEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): MonicaImeCardWalletEntry? {
        val data = CardWalletDataCodec.parseBankCardData(
            raw = itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        ) ?: return null
        val fields = listOfNotNull(
            fieldOrNull(strings.get(takagi.ru.monica.R.string.card_number), resolveSecretValue(data.cardNumber)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.cardholder_name), data.cardholderName),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.expiry_date), formatExpiry(data.expiryMonth, data.expiryYear)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.cvv), resolveSecretValue(data.cvv)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.bank_card_pin_label), resolveSecretValue(data.pin)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.bank_card_account_number_label), resolveSecretValue(data.accountNumber)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.bank_card_routing_number_label), data.routingNumber),
            fieldOrNull("IBAN", data.iban),
            fieldOrNull("SWIFT/BIC", data.swiftBic)
        )
        if (fields.isEmpty()) return null
        val title = title.ifBlank {
            data.nickname.ifBlank {
                data.bankName.ifBlank { strings.get(takagi.ru.monica.R.string.bank_card_default_title) }
            }
        }
        val subtitle = listOf(data.bankName, maskCardNumber(resolveSecretValue(data.cardNumber).orEmpty()))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return MonicaImeCardWalletEntry(
            id = id,
            title = title,
            subtitle = subtitle,
            typeLabel = strings.get(takagi.ru.monica.R.string.item_type_bank_card),
            isFavorite = isFavorite,
            sourceLabel = resolveSourceLabel(
                item = this,
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            ),
            fields = fields,
            keepassDatabaseId = keepassDatabaseId,
            mdbxDatabaseId = mdbxDatabaseId,
            bitwardenVaultId = bitwardenVaultId
        )
    }

    private fun SecureItem.toImeDocumentEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): MonicaImeCardWalletEntry? {
        val data = CardWalletDataCodec.parseDocumentData(
            raw = itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        ) ?: return null
        val fullName = data.displayName()
        val fields = listOfNotNull(
            fieldOrNull(strings.get(takagi.ru.monica.R.string.document_number), resolveSecretValue(data.documentNumber)),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.full_name), fullName),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.expiry_date_label), data.expiryDate),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.cardholder_label), data.username),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.email), data.email),
            fieldOrNull(strings.get(takagi.ru.monica.R.string.phone), data.phone),
            fieldOrNull("SSN", resolveSecretValue(data.ssn))
        )
        if (fields.isEmpty()) return null
        return MonicaImeCardWalletEntry(
            id = id,
            title = title.ifBlank { fullName.ifBlank { strings.get(takagi.ru.monica.R.string.documents) } },
            subtitle = fullName,
            typeLabel = strings.get(takagi.ru.monica.R.string.documents),
            isFavorite = isFavorite,
            sourceLabel = resolveSourceLabel(
                item = this,
                keepassLookup = keepassLookup,
                mdbxLookup = mdbxLookup,
                bitwardenLookup = bitwardenLookup,
                localLabel = localLabel,
                keepassLabel = keepassLabel,
                mdbxLabel = mdbxLabel,
                bitwardenLabel = bitwardenLabel
            ),
            fields = fields,
            keepassDatabaseId = keepassDatabaseId,
            mdbxDatabaseId = mdbxDatabaseId,
            bitwardenVaultId = bitwardenVaultId
        )
    }

    private fun ImePasswordRow.toImeEntryOrNull(
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): ImeRefreshResult? {
        val decryptedUsername = resolveFillableField(username)
        if (decryptedUsername.isNullOrBlank() && password.isBlank()) {
            return null
        }

        // OTP is resolved from the latest record when filled, not generated for every cached row.
        return ImeRefreshResult(
            value = MonicaImePasswordEntry(
                id = id,
                title = title,
                username = decryptedUsername.orEmpty(),
                website = website,
                packageName = appPackageName,
                appName = appName,
                password = password,
                isFavorite = isFavorite,
                hasTotp = authenticatorKey.isNotBlank(),
                sourceLabel = resolveSourceLabel(
                    entry = this,
                    keepassLookup = keepassLookup,
                    mdbxLookup = mdbxLookup,
                    bitwardenLookup = bitwardenLookup,
                    localLabel = localLabel,
                    keepassLabel = keepassLabel,
                    mdbxLabel = mdbxLabel,
                    bitwardenLabel = bitwardenLabel
                ),
                keepassDatabaseId = keepassDatabaseId,
                mdbxDatabaseId = mdbxDatabaseId,
                bitwardenVaultId = bitwardenVaultId
            )
        )
    }

    private fun resolveSourceLabel(
        entry: ImePasswordRow,
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): String {
        return when {
            entry.bitwardenVaultId != null -> {
                val vaultName = bitwardenLookup[entry.bitwardenVaultId]?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: bitwardenLookup[entry.bitwardenVaultId]?.email
                listOf(bitwardenLabel, vaultName).filterNotNull().joinToString(" · ")
            }
            entry.keepassDatabaseId != null -> {
                val databaseName = keepassLookup[entry.keepassDatabaseId]?.name
                listOf(keepassLabel, databaseName).filterNotNull().joinToString(" · ")
            }
            entry.mdbxDatabaseId != null -> {
                val databaseName = mdbxLookup[entry.mdbxDatabaseId]?.name
                listOf(mdbxLabel, databaseName).filterNotNull().joinToString(" · ")
            }
            else -> localLabel
        }
    }

    private fun resolveSourceLabel(
        item: SecureItem,
        keepassLookup: Map<Long, LocalKeePassDatabase>,
        mdbxLookup: Map<Long, LocalMdbxDatabase>,
        bitwardenLookup: Map<Long, BitwardenVault>,
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String
    ): String {
        return when {
            item.bitwardenVaultId != null -> {
                val vaultName = bitwardenLookup[item.bitwardenVaultId]?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: bitwardenLookup[item.bitwardenVaultId]?.email
                listOf(bitwardenLabel, vaultName).filterNotNull().joinToString(" · ")
            }
            item.keepassDatabaseId != null -> {
                val databaseName = keepassLookup[item.keepassDatabaseId]?.name
                listOf(keepassLabel, databaseName).filterNotNull().joinToString(" · ")
            }
            item.mdbxDatabaseId != null -> {
                val databaseName = mdbxLookup[item.mdbxDatabaseId]?.name
                listOf(mdbxLabel, databaseName).filterNotNull().joinToString(" · ")
            }
            else -> localLabel
        }
    }

    private fun resolveSecretValue(value: String): String? {
        return AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = value,
            logTag = "MonicaIme"
        )
    }

    private fun invalidateVaultSourceCache() {
        vaultGeneration++
        vaultSourceCache = null
        totpSourceCache = null
        cardWalletSourceCache = null
        loadedVaultPanels.clear()
    }

    private fun MonicaImePanel.requiresInitialVaultLoading(): Boolean {
        return isVaultContentPanel() && this !in loadedVaultPanels
    }

    private fun resolveFillableField(value: String): String? {
        return AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = value,
            logTag = "MonicaIme"
        )
    }

    private fun fieldOrNull(label: String, value: String?): MonicaImeCardWalletField? {
        val resolved = value?.trim().orEmpty()
        if (resolved.isBlank()) return null
        return MonicaImeCardWalletField(label = label, value = resolved)
    }

    private fun formatExpiry(month: String, year: String): String {
        val normalizedMonth = month.trim()
        val normalizedYear = year.trim()
        return when {
            normalizedMonth.isNotBlank() && normalizedYear.isNotBlank() -> "$normalizedMonth/$normalizedYear"
            normalizedMonth.isNotBlank() -> normalizedMonth
            else -> normalizedYear
        }
    }

    private fun maskCardNumber(cardNumber: String): String {
        val digits = cardNumber.filter { it.isDigit() }
        if (digits.length < 4) return ""
        return "•••• ${digits.takeLast(4)}"
    }

    private fun DocumentData.displayName(): String {
        val parts = listOf(firstName, middleName, lastName).filter { it.isNotBlank() }
        return when {
            parts.isNotEmpty() -> parts.joinToString(" ")
            fullName.isNotBlank() -> fullName
            else -> ""
        }
    }

    private fun buildDatabaseOptions(
        localLabel: String,
        keepassLabel: String,
        mdbxLabel: String,
        bitwardenLabel: String,
        allDatabasesLabel: String,
        keepassDatabases: List<LocalKeePassDatabase>,
        mdbxDatabases: List<LocalMdbxDatabase>,
        bitwardenVaults: List<BitwardenVault>
    ): List<MonicaImeDatabaseOption> {
        return buildList {
            add(MonicaImeDatabaseOption(MonicaImeDatabaseScope.All, allDatabasesLabel))
            add(MonicaImeDatabaseOption(MonicaImeDatabaseScope.Local, localLabel))
            keepassDatabases
                .sortedWith(
                    compareByDescending<LocalKeePassDatabase> { it.isDefault }
                        .thenBy { it.sortOrder }
                        .thenBy { it.name.lowercase() }
                )
                .forEach { database ->
                    add(
                        MonicaImeDatabaseOption(
                            scope = MonicaImeDatabaseScope.KeePass(database.id),
                            label = "$keepassLabel · ${database.name}"
                        )
                    )
                }
            mdbxDatabases
                .sortedWith(
                    compareByDescending<LocalMdbxDatabase> { it.isDefault }
                        .thenBy { it.sortOrder }
                        .thenBy { it.name.lowercase() }
                )
                .forEach { database ->
                    add(
                        MonicaImeDatabaseOption(
                            scope = MonicaImeDatabaseScope.Mdbx(database.id),
                            label = "$mdbxLabel · ${database.name}"
                        )
                    )
                }
            bitwardenVaults
                .sortedWith(
                    compareByDescending<BitwardenVault> { it.isDefault }
                        .thenBy { (it.displayName ?: it.email).lowercase() }
                )
                .forEach { vault ->
                    val vaultName = vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email
                    add(
                        MonicaImeDatabaseOption(
                            scope = MonicaImeDatabaseScope.Bitwarden(vault.id),
                            label = "$bitwardenLabel · $vaultName"
                        )
                    )
                }
        }
    }

    private fun switchToNextInputMethod() {
        clearPendingDeleteUndo()
        val switched = trySwitchToPreviousInputMethod()
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    @Suppress("DEPRECATION")
    private fun trySwitchToPreviousInputMethod(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        } else {
            val token = window?.window?.attributes?.token ?: return false
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            runCatching { imm.switchToLastInputMethod(token) }.getOrDefault(false)
        }
    }

    private fun startImeSearchEditing() {
        clearPendingDeleteUndo()
        uiState.update { it.startVaultSearch() }
    }

    private fun finishImeSearchEditing() {
        if (!uiState.value.isSearchEditing) return
        uiState.update { it.copy(isSearchEditing = false) }
        requestRefreshVaultEntries()
    }

    private fun clearImeSearchQuery() {
        val currentState = uiState.value
        if (currentState.query.isEmpty()) return
        uiState.update { it.copy(query = "") }
        requestRefreshVaultEntries()
    }

    private fun updateImeSearchQuery(nextQuery: String) {
        val currentState = uiState.value
        if (!currentState.isSearchEditing || nextQuery == currentState.query) return
        uiState.update { it.copy(query = nextQuery) }
        requestRefreshVaultEntries(debounceMillis = ImeSearchRefreshDebounceMs)
    }

    private fun handleKeyPress(text: String) {
        val currentState = uiState.value
        if (currentState.isSearchEditing) {
            updateImeSearchQuery(appendImeSearchQuery(currentState.query, text))
            return
        }
        clearPendingDeleteUndo()
        commitExternalText(text)
    }

    private fun commitExternalText(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun handleSmartFillPassword(entry: MonicaImePasswordEntry) {
        clearPendingDeleteUndo()
        val username = resolveFillableField(entry.username).orEmpty()
        val password = resolveFillableField(entry.password).orEmpty()
        val values = if (isCurrentFieldLikelyPassword()) {
            listOf(password)
        } else {
            listOf(username, password)
        }.filter { it.isNotBlank() }
        performSequentialImeFill(values)
    }

    private fun handleSmartFillCardWallet(entry: MonicaImeCardWalletEntry) {
        clearPendingDeleteUndo()
        performSequentialImeFill(entry.fields.map { it.value }.filter { it.isNotBlank() })
    }

    private fun performSequentialImeFill(values: List<String>) {
        if (values.isEmpty()) return
        serviceScope.launch {
            values.forEachIndexed { index, value ->
                val connection = currentInputConnection ?: return@launch
                val editor = currentInputEditorInfo ?: return@launch
                val packageName = editor.packageName
                val fieldId = editor.fieldId
                val fieldName = editor.fieldName
                val inputType = editor.inputType
                val hint = editor.hintText?.toString()
                if (!connection.commitText(value, 1)) return@launch
                if (index < values.lastIndex) {
                    delay(ImeSequentialFillStepDelayMs)
                    if (!moveToNextInputField(connection)) {
                        return@launch
                    }
                    delay(ImeSequentialFillFocusDelayMs)
                    val nextEditor = currentInputEditorInfo ?: return@launch
                    if (nextEditor.packageName != packageName) return@launch
                    // IME_ACTION_NEXT=true only means the event was consumed.
                    // Enterprise forms sometimes consume it without moving focus.
                    val moved = if (fieldId > 0 && nextEditor.fieldId > 0) {
                        nextEditor.fieldId != fieldId
                    } else {
                        // restartInput can replace the connection for the same
                        // editor. Require evidence that the target changed.
                        nextEditor.fieldName != fieldName ||
                            nextEditor.inputType != inputType || nextEditor.hintText?.toString() != hint
                    }
                    if (!moved) return@launch
                }
            }
        }
    }

    private fun moveToNextInputField(connection: android.view.inputmethod.InputConnection): Boolean {
        if (connection.performEditorAction(EditorInfo.IME_ACTION_NEXT)) {
            return true
        }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)
        return connection.sendKeyEvent(down) && connection.sendKeyEvent(up)
    }

    private fun isCurrentFieldLikelyPassword(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun handleBackspace() {
        val currentState = uiState.value
        if (currentState.isSearchEditing) {
            updateImeSearchQuery(removeLastImeSearchCharacter(currentState.query))
            return
        }
        clearPendingDeleteUndo()
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun handleDeleteAll() {
        if (uiState.value.isSearchEditing) {
            clearImeSearchQuery()
            return
        }
        val connection = currentInputConnection ?: return
        val beforeCursor = connection.getTextBeforeCursor(MaxImeClearChars, 0)?.toString().orEmpty()
        val selectedText = connection.getSelectedText(0)?.toString().orEmpty()
        val afterCursor = connection.getTextAfterCursor(MaxImeClearChars, 0)?.toString().orEmpty()
        val fullText = beforeCursor + selectedText + afterCursor
        if (fullText.isEmpty()) return

        connection.beginBatchEdit()
        if (selectedText.isNotEmpty()) {
            connection.commitText("", 1)
        }
        connection.deleteSurroundingText(beforeCursor.length, afterCursor.length)
        connection.endBatchEdit()

        pendingClearedInputText = fullText
        uiState.update { it.copy(pendingClearedInput = fullText) }
    }

    private fun handleUndoDeleteAll() {
        val textToRestore = pendingClearedInputText ?: return
        pendingClearedInputText = null
        uiState.update { it.copy(pendingClearedInput = null) }
        currentInputConnection?.commitText(textToRestore, 1)
    }

    private fun handleEnter() {
        if (uiState.value.isSearchEditing) {
            finishImeSearchEditing()
            return
        }
        clearPendingDeleteUndo()
        val connection = currentInputConnection ?: return
        if (!connection.performEditorAction(EditorInfo.IME_ACTION_DONE)) {
            connection.commitText("\n", 1)
        }
    }

    private fun clearPendingDeleteUndo() {
        if (pendingClearedInputText == null && uiState.value.pendingClearedInput == null) return
        pendingClearedInputText = null
        uiState.update { it.copy(pendingClearedInput = null) }
    }
}

private const val MaxImeClearChars = 10_000
private const val ImeSequentialFillStepDelayMs = 90L
private const val ImeSequentialFillFocusDelayMs = 140L
private const val ImeSearchRefreshDebounceMs = 90L
private const val MaxImeSearchCodePoints = 80
internal fun imePasswordEntryMatchesQuery(
    entry: MonicaImePasswordEntry,
    rawQuery: String
): Boolean = ImeSearchQuery(rawQuery).matchesPassword(entry)

internal fun sortImePasswordEntries(
    entries: List<MonicaImePasswordEntry>,
    sortMode: MonicaImePasswordSortMode,
    activePackageName: String
): List<MonicaImePasswordEntry> = ImePasswordIndex(entries).query(
    rawQuery = "", sortMode = sortMode, activePackageName = activePackageName)

internal fun imePasswordAlphabeticalLabel(entry: MonicaImePasswordEntry): String {
    return entry.title.ifBlank {
        entry.website.ifBlank { entry.username }
    }
}

internal fun imeAuthenticatorAlphabeticalLabel(entry: MonicaImeAuthenticatorEntry): String {
    return entry.title.ifBlank {
        entry.issuer.ifBlank {
            entry.accountName.ifBlank { entry.sourceLabel }
        }
    }
}

internal fun imeCardWalletAlphabeticalLabel(entry: MonicaImeCardWalletEntry): String {
    return entry.title.ifBlank {
        entry.subtitle.ifBlank { entry.typeLabel }
    }
}

internal fun appendImeSearchQuery(currentQuery: String, text: String): String {
    if (text.isEmpty()) return currentQuery
    val combined = currentQuery + text
    val codePointCount = combined.codePointCount(0, combined.length)
    if (codePointCount <= MaxImeSearchCodePoints) return combined
    val endIndex = combined.offsetByCodePoints(0, MaxImeSearchCodePoints)
    return combined.substring(0, endIndex)
}

internal fun removeLastImeSearchCharacter(currentQuery: String): String {
    if (currentQuery.isEmpty()) return currentQuery
    val endIndex = currentQuery.offsetByCodePoints(currentQuery.length, -1)
    return currentQuery.substring(0, endIndex)
}

private data class ImeRefreshResult(
    val value: MonicaImePasswordEntry
)

private data class ImeRefreshRequest(
    val panel: MonicaImePanel,
    val presentation: ImeVaultPresentation,
    val generation: Long,
)

private data class ImeRefreshSnapshot(
    val sources: ImeVaultSourceCache,
    val totpSources: List<SecureItem>?,
    val cardSources: List<SecureItem>?,
    val results: List<ImeRefreshResult>,
    val authenticatorResults: List<MonicaImeAuthenticatorEntry>,
    val cardWalletResults: List<MonicaImeCardWalletEntry>,
    val databaseOptions: List<MonicaImeDatabaseOption>,
    val selectedScope: MonicaImeDatabaseScope
)

private data class ImeVaultSourceCache(
    val passwordEntries: List<ImePasswordRow>,
    val passwordResults: List<ImeRefreshResult>,
    val passwordIndex: ImePasswordIndex,
    val keepassLookup: Map<Long, LocalKeePassDatabase>,
    val mdbxLookup: Map<Long, LocalMdbxDatabase>,
    val bitwardenLookup: Map<Long, BitwardenVault>,
    val databaseOptions: List<MonicaImeDatabaseOption>,
)

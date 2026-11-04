package takagi.ru.monica.bitwarden.viewmodel

import takagi.ru.monica.utils.AppLocaleStringResolver
import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.cache.BitwardenOfflineSecretCache
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.LoginResult
import takagi.ru.monica.bitwarden.sync.BitwardenCoordinatedSyncResult
import takagi.ru.monica.bitwarden.sync.BitwardenPageAutoSyncScheduler
import takagi.ru.monica.bitwarden.sync.BitwardenAutoSyncTargetPlanner
import takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge
import takagi.ru.monica.bitwarden.sync.BitwardenSyncOrchestrator
import takagi.ru.monica.bitwarden.sync.BitwardenSyncNotificationHelper
import takagi.ru.monica.bitwarden.sync.BitwardenSyncSummary
import takagi.ru.monica.bitwarden.sync.NetworkGateResult
import takagi.ru.monica.bitwarden.sync.classifyBitwardenSyncError
import takagi.ru.monica.bitwarden.sync.SyncBlockReason
import takagi.ru.monica.bitwarden.sync.SyncExecutionOutcome
import takagi.ru.monica.bitwarden.sync.SyncTriggerReason
import takagi.ru.monica.bitwarden.sync.VaultSyncStatus
import takagi.ru.monica.bitwarden.sync.mergeBitwardenSyncStatuses
import takagi.ru.monica.bitwarden.sync.syncViaCoordinator
import takagi.ru.monica.bitwarden.sync.toBitwardenBlockReason
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenConflictBackup
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.OperationLogger

/**
 * Bitwarden ViewModel
 * 
 * 管理 Bitwarden 相关的 UI 状态和用户操作
 * 
 * 主要功能：
 * 1. 登录/登出流程
 * 2. Vault 解锁/锁定
 * 3. 同步状态管理
 * 4. 密码条目和文件夹的访问
 * 5. 冲突解决
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BitwardenViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "BitwardenViewModel"
        private const val COLD_START_AUTO_SYNC_GRACE_MS = 8_000L
        private const val MULTI_VAULT_AUTO_SYNC_STAGGER_MS = 5_000L
        private const val STARTUP_AUTO_SYNC_UNLOCK_WAIT_MS = 800L
        private const val SILENT_SYNC_UI_REFRESH_DELAY_MS = 1_500L
        private const val SILENT_SYNC_CACHE_WARM_DELAY_MS = 12_000L
    }
    
    // 仓库
    private val strings = AppLocaleStringResolver(application)
    private val repository = BitwardenRepository.getInstance(application)
    val syncIssueReports = repository.syncIssueReports
    private val accountService = takagi.ru.monica.bitwarden.service.BitwardenAccountService(application)

    internal suspend fun startAccountRegistration(server: String, email: String, name: String?, tls: takagi.ru.monica.bitwarden.api.BitwardenTlsConfig?) =
        accountService.startRegistration(server, email, name, tls)

    internal suspend fun verifyAccountRegistration(ticket: takagi.ru.monica.bitwarden.service.BitwardenRegistrationTicket, token: String) =
        accountService.verifyRegistration(ticket, token)

    internal suspend fun finishAccountRegistration(ticket: takagi.ru.monica.bitwarden.service.BitwardenRegistrationTicket, password: String, confirmation: String, hint: String?) =
        accountService.finishRegistration(ticket, password, confirmation, hint)

    internal suspend fun requestAccountPasswordHint(server: String, email: String, tls: takagi.ru.monica.bitwarden.api.BitwardenTlsConfig?) =
        accountService.requestPasswordHint(server, email, tls)

    suspend fun getAccountDevices(vaultId: Long) = repository.getAccountDevices(vaultId)
    private val securityManager = SecurityManager(application.applicationContext)
    private val bitwardenOfflineSecretCache = BitwardenOfflineSecretCache(
        application.applicationContext,
        securityManager
    )
    private val decryptLock = Any()
    
    // 两步验证临时状态
    private var twoFactorState: LoginResult.TwoFactorRequired? = null
    private var authenticationJob: Job? = null
    private val _sendingTwoFactorEmail = MutableStateFlow(false)
    val sendingTwoFactorEmail = _sendingTwoFactorEmail.asStateFlow()
    private var pendingServerUrl: String? = null
    private var pendingTlsConfig: BitwardenTlsConfig? = null
    private val processStartMs = System.currentTimeMillis()
    private var silentPostSyncRefreshJob: Job? = null
    private val silentCacheWarmJobs = mutableMapOf<Long, Job>()
    
    // ==================== UI 状态 ====================
    
    // 登录状态
    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()
    
    // Vault 列表
    private val _vaults = MutableStateFlow<List<BitwardenVault>>(emptyList())
    val vaults: StateFlow<List<BitwardenVault>> = _vaults.asStateFlow()
    
    // 当前活跃 Vault
    private val _activeVault = MutableStateFlow<BitwardenVault?>(null)
    val activeVault: StateFlow<BitwardenVault?> = _activeVault.asStateFlow()
    
    // 当前活跃 Vault 的解锁状态
    private val _unlockState = MutableStateFlow<UnlockState>(UnlockState.Locked)
    val unlockState: StateFlow<UnlockState> = _unlockState.asStateFlow()

    // 每个 Vault 的解锁状态
    private val _unlockStateByVault = MutableStateFlow<Map<Long, UnlockState>>(emptyMap())
    val unlockStateByVault: StateFlow<Map<Long, UnlockState>> = _unlockStateByVault.asStateFlow()
    
    // 同步状态
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    // 密码条目列表
    private val _entries = MutableStateFlow<List<PasswordEntry>>(emptyList())
    val entries: StateFlow<List<PasswordEntry>> = _entries.asStateFlow()
    
    // 文件夹列表
    private val _folders = MutableStateFlow<List<BitwardenFolder>>(emptyList())
    val folders: StateFlow<List<BitwardenFolder>> = _folders.asStateFlow()

    // Send 列表（仅当前活跃 Vault；保留以兼容旧调用方，新代码请使用 sendsAcrossVaults）
    private val _sends = MutableStateFlow<List<BitwardenSend>>(emptyList())
    val sends: StateFlow<List<BitwardenSend>> = _sends.asStateFlow()

    // Send 跨 Vault 列表（合并所有已解锁账号下的 Send）
    private val _sendsAcrossVaults = MutableStateFlow<List<BitwardenSend>>(emptyList())
    val sendsAcrossVaults: StateFlow<List<BitwardenSend>> = _sendsAcrossVaults.asStateFlow()

    // Send 页面状态
    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _sendCreateSuccessVersion = MutableStateFlow(0)
    val sendCreateSuccessVersion: StateFlow<Int> = _sendCreateSuccessVersion.asStateFlow()
    
    // 冲突列表
    private val _conflicts = MutableStateFlow<List<BitwardenConflictBackup>>(emptyList())
    val conflicts: StateFlow<List<BitwardenConflictBackup>> = _conflicts.asStateFlow()
    
    // 搜索结果
    private val _searchResults = MutableStateFlow<List<PasswordEntry>>(emptyList())
    val searchResults: StateFlow<List<PasswordEntry>> = _searchResults.asStateFlow()
    
    // 当前选中的文件夹
    private val _selectedFolder = MutableStateFlow<BitwardenFolder?>(null)
    val selectedFolder: StateFlow<BitwardenFolder?> = _selectedFolder.asStateFlow()
    
    // 永不锁定设置状态
    private val _isNeverLockEnabled = MutableStateFlow(false)
    val isNeverLockEnabledFlow: StateFlow<Boolean> = _isNeverLockEnabled.asStateFlow()

    // 同步设置状态（用于界面实时更新）
    private val _isAutoSyncEnabled = MutableStateFlow(false)
    val isAutoSyncEnabledFlow: StateFlow<Boolean> = _isAutoSyncEnabled.asStateFlow()

    private val _isSyncOnWifiOnly = MutableStateFlow(false)
    val isSyncOnWifiOnlyFlow: StateFlow<Boolean> = _isSyncOnWifiOnly.asStateFlow()
    
    // 一次性事件
    private val _events = MutableSharedFlow<BitwardenEvent>()
    val events = _events.asSharedFlow()
    
    private val syncOrchestrator = BitwardenSyncOrchestrator(
        strings = strings,
        scope = viewModelScope,
        isAutoSyncEnabled = { repository.isAutoSyncEnabled },
        checkNetwork = { evaluateNetworkGate() },
        isVaultUnlocked = { vaultId -> repository.isVaultUnlocked(vaultId) },
        executeSync = { vaultId, silent -> runSync(vaultId = vaultId, silent = silent) }
    )
    private val pageAutoSyncScheduler = BitwardenPageAutoSyncScheduler(
        scope = viewModelScope,
        delayBetweenVaultsMs = MULTI_VAULT_AUTO_SYNC_STAGGER_MS,
        requestSync = { vaultId, reason ->
            syncOrchestrator.requestSync(
                vaultId = vaultId,
                reason = reason
            )
        }
    )
    val syncStatusByVault: StateFlow<Map<Long, VaultSyncStatus>> = combine(
        syncOrchestrator.statusByVault,
        SyncTaskRunner.statuses
    ) { orchestratorStatuses, coordinatorStatuses ->
        mergeBitwardenSyncStatuses(orchestratorStatuses, coordinatorStatuses.values)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )
    

    private val syncQueueReader by lazy {
        takagi.ru.monica.bitwarden.sync.BitwardenSyncQueueReader(
            takagi.ru.monica.data.PasswordDatabase.getDatabase(application), repository, application)
    }
    internal val syncQueueByVault = syncQueueReader.observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(0, 0), emptyMap())
    internal val syncQueueSummary = combine(syncQueueByVault, syncIssueReports, vaults,
        takagi.ru.monica.security.SessionManager.lockGeneration,
        takagi.ru.monica.security.SessionManager.isUnlocked) { snapshots, reports, accounts, _, _ ->
        takagi.ru.monica.bitwarden.sync.summarizeBitwardenQueue(
            snapshots.values.filter { snapshot -> syncQueueReader.isCurrent(snapshot, accounts.firstOrNull { it.id == snapshot.vaultId }) },
            reports)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0),
        takagi.ru.monica.bitwarden.sync.BitwardenSyncQueueSummary())

    internal fun isSyncQueueCurrent(snapshot: takagi.ru.monica.bitwarden.sync.BitwardenSyncQueueSnapshot): Boolean =
        syncQueueReader.isCurrent(snapshot, vaults.value.firstOrNull { it.id == snapshot.vaultId })

    private val sendDraftStore = takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore(getApplication())
    private val _localSendDrafts = MutableStateFlow<List<takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore.Draft>>(emptyList())
    val localSendDrafts = _localSendDrafts.asStateFlow()
    private val _localSendDraftsUnavailable = MutableStateFlow(false)
    val localSendDraftsUnavailable = _localSendDraftsUnavailable.asStateFlow()
    private var sendDraftReadVersion = 0L
    private var storedSendSubmissionJob: Job? = null

    fun leaveSendEditorForeground() {
        storedSendSubmissionJob?.cancel()
        storedSendSubmissionJob = null
        sendDraftReadVersion++
        _localSendDrafts.value = emptyList()
        if (_sendState.value is SendState.Creating) _sendState.value = SendState.Idle
    }

    fun refreshLocalSendDrafts() { viewModelScope.launch { readLocalSendDrafts() } }

    private suspend fun readLocalSendDrafts() {
        val version = ++sendDraftReadVersion
        val lock = takagi.ru.monica.security.SessionManager.lockGeneration.value
        if (!takagi.ru.monica.security.SessionManager.isUnlocked.value) {
            _localSendDrafts.value = emptyList(); _localSendDraftsUnavailable.value = false
            return
        }
        val accounts = repository.getAllVaults().filter { !it.isLocked && repository.isVaultUnlocked(it.id) }
        val generations = accounts.associate { it.id to repository.getVaultSessionGeneration(it.id) }
        val drafts = mutableListOf<takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore.Draft>()
        var unavailable = false
        for (vault in accounts) {
            try { sendDraftStore.listDetailed(vault.id).also { drafts += it.drafts; unavailable = unavailable || it.unavailable > 0 } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable = true }
        }
        val currentAccounts = repository.getAllVaults().associateBy { it.id }
        if (version != sendDraftReadVersion || !takagi.ru.monica.security.SessionManager.isUnlocked.value ||
            takagi.ru.monica.security.SessionManager.lockGeneration.value != lock) return
        val current = drafts.filter { draft ->
            val vault = currentAccounts[draft.vaultId]
            vault != null && !vault.isLocked && repository.isVaultUnlocked(vault.id) &&
                generations[vault.id] == repository.getVaultSessionGeneration(vault.id) &&
                draft.account == takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity.from(vault)
        }
        _localSendDrafts.value = current.sortedByDescending { it.updatedAt }
        _localSendDraftsUnavailable.value = unavailable
    }

    fun discardLocalSendDraft(saved: takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore.Draft) {
        viewModelScope.launch {
            try { sendDraftStore.discard(saved.vaultId, saved.id); readLocalSendDrafts() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _events.emit(BitwardenEvent.ShowError(strings.get(R.string.bw_send_draft_failed))) }
        }
    }

    /** Reload the encrypted record before every manual attempt; never submit on startup or reconnect. */
    fun submitStoredSendDraft(selected: takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore.Draft) {
        if (storedSendSubmissionJob?.isActive == true) return
        storedSendSubmissionJob = viewModelScope.launch {
            _sendState.value = SendState.Creating
            try {
                val saved = sendDraftStore.load(selected.vaultId, selected.id)
                val draft = saved.draft
                if (!saved.submissionUncertain) {
                    draft.validate(System.currentTimeMillis(), saved.type == BitwardenSend.TYPE_TEXT)
                    require(saved.accessLimitInput.isNullOrEmpty() || saved.accessLimitInput.toIntOrNull()?.let { it > 0 } == true)
                }
                val result = when {
                    saved.submissionUncertain -> repository.reconcileSendDraft(saved)
                    saved.original != null -> repository.updateSend(saved.original, draft) {
                        sendDraftStore.markSubmitting(saved.vaultId, saved.id, it)
                    }
                    saved.type == BitwardenSend.TYPE_FILE -> {
                        val file = requireNotNull(saved.file)
                        repository.createFileSend(saved.vaultId, draft.title, null, file.name, draft.notes,
                            draft.password, draft.maxAccessCount, draft.hideEmail, draft.deletionMillis, draft.expirationMillis,
                            authAction = draft.authAction, recipientEmails = draft.recipientEmails, disabled = draft.disabled,
                            sourceSize = file.size, source = { sendDraftStore.openFile(saved.vaultId, saved.id) },
                            beforeRequest = { sendDraftStore.markSubmitting(saved.vaultId, saved.id, it) },
                            onUploaded = { sendDraftStore.markFileUploadConfirmed(saved.vaultId, saved.id) })
                    }
                    else -> repository.createTextSend(saved.vaultId, draft.title, draft.text, draft.notes, draft.password,
                        draft.maxAccessCount, draft.hideEmail, draft.hiddenText, draft.deletionMillis, draft.expirationMillis,
                        authAction = draft.authAction, recipientEmails = draft.recipientEmails, disabled = draft.disabled,
                        beforeRequest = { sendDraftStore.markSubmitting(saved.vaultId, saved.id, it) })
                }
                when (result) {
                    is BitwardenRepository.SendMutationResult.Success -> {
                        // A lock/account change prevents cleanup and leaves an uncertain draft recoverable.
                        sendDraftStore.discard(saved.vaultId, saved.id)
                        refreshSendsAcrossVaults()
                        if (_activeVault.value?.id == saved.vaultId) _sends.value = repository.getSends(saved.vaultId)
                        _sendState.value = SendState.Idle
                        _sendCreateSuccessVersion.value += 1
                        requestLocalMutationSync(saved.vaultId)
                        _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.send_saved)))
                    }
                    is BitwardenRepository.SendMutationResult.Error -> {
                        if (!result.uncertain) sendDraftStore.markKnownFailure(saved.vaultId, saved.id)
                        _sendState.value = SendState.Error(result.message)
                        _events.emit(BitwardenEvent.ShowError(result.message))
                    }
                    is BitwardenRepository.SendMutationResult.Deleted -> error("Unexpected Send deletion")
                }
                readLocalSendDrafts()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                _sendState.value = if (takagi.ru.monica.security.SessionManager.isUnlocked.value)
                    SendState.Error(strings.get(R.string.bw_send_draft_failed)) else SendState.Locked
            }
        }
    }


    init {
        viewModelScope.launch {
            repository.getAllVaultsFlow().map { it.map { vault -> vault.id }.sorted() }.distinctUntilChanged()
                .flatMapLatest { repository.observeSendsForVaults(it) }.collect { rows ->
                    _sendsAcrossVaults.value = rows
                    _sends.value = rows.filter { it.vaultId == _activeVault.value?.id }
                }
        }
        viewModelScope.launch {
            combine(_vaults, _unlockStateByVault, takagi.ru.monica.security.SessionManager.isUnlocked,
                takagi.ru.monica.security.SessionManager.lockGeneration) { _, _, _, _ -> Unit }
                .collectLatest {
                    _localSendDrafts.value = emptyList()
                    readLocalSendDrafts()
                }
        }

        // 加载永不锁定设置
        _isNeverLockEnabled.value = repository.isNeverLockEnabled
        _isAutoSyncEnabled.value = repository.isAutoSyncEnabled
        _isSyncOnWifiOnly.value = repository.isSyncOnWifiOnly
        BitwardenMutationSyncBridge.register(this) { vaultId ->
            syncOrchestrator.requestSync(
                vaultId = vaultId,
                reason = SyncTriggerReason.LOCAL_MUTATION
            )
            true
        }
        observeVaultSnapshots()
        loadVaults(
            triggerStartupAutoSync = false,
            triggerActiveVaultAutoSync = false
        )
    }

    override fun onCleared() {
        pageAutoSyncScheduler.cancelPending()
        BitwardenMutationSyncBridge.unregister(this)
        super.onCleared()
    }
    
    // ==================== 公开方法 ====================
    
    /**
     * 加载 Vault 列表
     */
    fun loadVaults(
        triggerStartupAutoSync: Boolean = false,
        triggerActiveVaultAutoSync: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val restoredVaultIds = if (_isNeverLockEnabled.value) {
                    repository.restoreUnlockedVaults()
                } else {
                    emptySet()
                }
                val vaultList = repository.getAllVaults()
                _vaults.value = vaultList
                replaceUnlockStates(vaultList)
                
                // 加载活跃 Vault
                val active = repository.getActiveVault()
                _activeVault.value = active

                val activeUnlockState = active?.let { currentUnlockState(it.id) } ?: UnlockState.Locked
                _unlockState.value = activeUnlockState

                if (active != null && activeUnlockState == UnlockState.Unlocked) {
                    _sendState.value = SendState.Idle
                    loadVaultData(active.id)
                    if (active.id in restoredVaultIds) {
                        Log.d(TAG, "成功恢复 Vault 解锁状态")
                    }
                } else {
                    clearActiveVaultContent(
                        sendState = if (active == null) SendState.Idle else SendState.Locked
                    )
                }

                if (triggerStartupAutoSync && active != null && activeUnlockState == UnlockState.Unlocked) {
                    val reason = if (restoredVaultIds.isNotEmpty()) {
                        SyncTriggerReason.APP_RESUME
                    } else {
                        SyncTriggerReason.PAGE_ENTER
                    }
                    requestAutoSyncWithStartupGrace(active.id, reason)
                } else if (triggerActiveVaultAutoSync && active != null && activeUnlockState == UnlockState.Unlocked) {
                    val trigger = if (active.id in restoredVaultIds) {
                        "loadVaults:restoredUnlock"
                    } else {
                        "loadVaults:activeUnlocked"
                    }
                    maybeTriggerSilentAutoSync(active, trigger = trigger)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 Vault 失败", e)
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_vault_load_failed, e.message ?: "")))
            }
        }
    }
    
    /**
     * 登录 Bitwarden
     */
    fun login(
        serverUrl: String?,
        email: String,
        masterPassword: String,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null
    ) {
        if (email.isBlank() || masterPassword.isBlank()) {
            viewModelScope.launch {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_bitwarden_credentials_required)))
            }
            return
        }
        
        authenticationJob?.cancel()
        authenticationJob = viewModelScope.launch {
            _loginState.value = LoginState.Loading
            
            val result = repository.login(
                serverUrl = serverUrl?.takeIf { it.isNotBlank() },
                email = email,
                masterPassword = masterPassword,
                captchaResponse = captchaResponse,
                tlsConfig = tlsConfig
            )
            
            when (result) {
                is BitwardenRepository.RepositoryLoginResult.Success -> {
                    _loginState.value = LoginState.Success(result.vault)
                    _activeVault.value = result.vault
                    setUnlockState(result.vault.id, UnlockState.Unlocked)
                    _unlockState.value = UnlockState.Unlocked
                    _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_login_success)))
                    _events.emit(BitwardenEvent.NavigateToVault(result.vault.id))
                    // 延迟加载以避免并发问题
                    kotlinx.coroutines.delay(100)
                    loadVaults()
                    loadVaultData(result.vault.id)
                }
                
                is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired -> {
                    twoFactorState = result.state
                    pendingServerUrl = serverUrl
                    pendingTlsConfig = tlsConfig
                    _loginState.value = LoginState.TwoFactorRequired(result.providers)
                    _events.emit(BitwardenEvent.ShowTwoFactorDialog(result.providers))
                }

                is BitwardenRepository.RepositoryLoginResult.CaptchaRequired -> {
                    if (!result.siteKey.isNullOrBlank()) {
                        _loginState.value = LoginState.Error(result.message)
                        _events.emit(
                            BitwardenEvent.ShowCaptchaDialog(
                                message = result.message,
                                forTwoFactor = false,
                                siteKey = result.siteKey
                            )
                        )
                    } else {
                        val message = strings.get(R.string.legacy_ui_login_challenge)
                        _loginState.value = LoginState.Error(message)
                        _events.emit(BitwardenEvent.ShowError(message))
                    }
                }
                
                is BitwardenRepository.RepositoryLoginResult.Error -> {
                    _loginState.value = LoginState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
            }
        }
    }
    
    /**
     * 使用两步验证登录
     */
    fun loginWithTwoFactor(
        twoFactorCode: String,
        twoFactorMethod: Int,
        captchaResponse: String? = null,
        remember: Boolean = false
    ) {
        val state = twoFactorState ?: run {
            viewModelScope.launch {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_two_factor_state_lost)))
            }
            return
        }
        
        authenticationJob?.cancel()
        authenticationJob = viewModelScope.launch {
            _loginState.value = LoginState.Loading
            
            val result = repository.loginWithTwoFactor(
                twoFactorState = state,
                twoFactorCode = twoFactorCode,
                twoFactorProvider = twoFactorMethod,
                serverUrl = pendingServerUrl,
                captchaResponse = captchaResponse,
                tlsConfig = pendingTlsConfig,
                remember = remember
            )
            
            when (result) {
                is BitwardenRepository.RepositoryLoginResult.Success -> {
                    twoFactorState = null
                    pendingServerUrl = null
                    pendingTlsConfig = null
                    _loginState.value = LoginState.Success(result.vault)
                    _activeVault.value = result.vault
                    setUnlockState(result.vault.id, UnlockState.Unlocked)
                    _unlockState.value = UnlockState.Unlocked
                    loadVaults()
                    loadVaultData(result.vault.id)
                    _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_login_success)))
                    _events.emit(BitwardenEvent.NavigateToVault(result.vault.id))
                }
                
                is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired -> {
                    _loginState.value = LoginState.TwoFactorRequired(result.providers)
                    twoFactorState = result.state
                    _events.emit(BitwardenEvent.ShowTwoFactorDialog(result.providers))
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_verification_code_incorrect)))
                }

                is BitwardenRepository.RepositoryLoginResult.CaptchaRequired -> {
                    if (!result.siteKey.isNullOrBlank()) {
                        _loginState.value = LoginState.Error(result.message)
                        _events.emit(
                            BitwardenEvent.ShowCaptchaDialog(
                                message = result.message,
                                forTwoFactor = true,
                                siteKey = result.siteKey
                            )
                        )
                    } else {
                        val message = strings.get(R.string.legacy_ui_two_factor_challenge)
                        _loginState.value = LoginState.Error(message)
                        _events.emit(BitwardenEvent.ShowError(message))
                    }
                }
                
                is BitwardenRepository.RepositoryLoginResult.Error -> {
                    _loginState.value = LoginState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
            }
        }
    }

    fun sendTwoFactorEmailLogin() {
        val state = twoFactorState ?: run {
            viewModelScope.launch {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_two_factor_state_lost)))
            }
            return
        }

        if (_sendingTwoFactorEmail.value) return
        _sendingTwoFactorEmail.value = true
        viewModelScope.launch {
            try {
            val result = repository.sendTwoFactorEmailLogin(
                twoFactorState = state,
                serverUrl = pendingServerUrl,
                tlsConfig = pendingTlsConfig
            )
            result.fold(
                onSuccess = {
                    _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_email_code_sent)))
                },
                onFailure = { error ->
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_email_code_send_failed, repository.describeLoginError(error))))
                }
            )
            } finally { _sendingTwoFactorEmail.value = false }
        }
    }
    
    /**
     * 解锁 Vault
     */
    fun unlock(masterPassword: String) {
        val vaultId = _activeVault.value?.id ?: return
        unlock(vaultId, masterPassword)
    }

    fun unlock(vaultId: Long, masterPassword: String, onResult: ((Boolean) -> Unit)? = null) {
        val vault = resolveVault(vaultId) ?: run { onResult?.invoke(false); return }
        val isActiveVault = _activeVault.value?.id == vaultId
        
        viewModelScope.launch {
            setUnlockState(vaultId, UnlockState.Unlocking)
            if (isActiveVault) {
                _unlockState.value = UnlockState.Unlocking
            }
            
            when (val result = repository.unlock(vault.id, masterPassword)) {
                is BitwardenRepository.UnlockResult.Success -> {
                    setUnlockState(vaultId, UnlockState.Unlocked)
                    if (isActiveVault) {
                        _unlockState.value = UnlockState.Unlocked
                        _sendState.value = SendState.Idle
                        loadVaultData(vault.id)
                        maybeTriggerSilentAutoSync(vault, trigger = "unlock")
                    }
                    onResult?.invoke(true)
                    _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_vault_unlocked)))
                }
                
                is BitwardenRepository.UnlockResult.Error -> {
                    setUnlockState(vaultId, UnlockState.Locked)
                    if (isActiveVault) {
                        _unlockState.value = UnlockState.Locked
                        clearActiveVaultContent(sendState = SendState.Locked)
                    }
                    onResult?.invoke(false)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
            }
        }
    }
    
    /**
     * 锁定 Vault
     */
    fun lock() {
        val vaultId = _activeVault.value?.id ?: return
        lock(vaultId)
    }

    fun lock(vaultId: Long) {
        val isActiveVault = _activeVault.value?.id == vaultId
        
        viewModelScope.launch {
            repository.lock(vaultId)
            syncOrchestrator.clearVault(vaultId)
            setUnlockState(vaultId, currentRepositoryUnlockState(vaultId))
            if (isActiveVault) {
                _unlockState.value = currentUnlockState(vaultId)
                clearActiveVaultContent(
                    sendState = if (currentUnlockState(vaultId) == UnlockState.Unlocked) {
                        SendState.Idle
                    } else {
                        SendState.Locked
                    }
                )
                if (currentUnlockState(vaultId) == UnlockState.Unlocked) {
                    resolveVault(vaultId)?.let { loadVaultData(it.id) }
                }
            }
            _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_vault_now_locked)))
        }
    }
    
    /**
     * 锁定所有 Vault
     */
    fun lockAll() {
        viewModelScope.launch {
            repository.lockAll()
            val currentVaults = repository.getAllVaults()
            _vaults.value = currentVaults
            currentVaults.forEach { syncOrchestrator.clearVault(it.id) }
            replaceUnlockStates(currentVaults)
            val active = _activeVault.value
            val activeUnlockState = active?.let { currentUnlockState(it.id) } ?: UnlockState.Locked
            _unlockState.value = activeUnlockState
            if (active != null && activeUnlockState == UnlockState.Unlocked) {
                _sendState.value = SendState.Idle
                loadVaultData(active.id)
            } else {
                clearActiveVaultContent(
                    sendState = if (active == null) SendState.Idle else SendState.Locked
                )
            }
        }
    }
    
    /**
     * 登出
     */
    fun logout(vaultId: Long? = null) {
        val targetVaultId = vaultId ?: _activeVault.value?.id ?: return
        
        viewModelScope.launch {
            val success = repository.logout(targetVaultId)
            if (success) {
                syncOrchestrator.clearVault(targetVaultId)
                loadVaults()
                removeUnlockState(targetVaultId)
                _loginState.value = LoginState.Idle
                _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_logged_out)))
                _events.emit(BitwardenEvent.NavigateToLogin)
            } else {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_logout_failed)))
            }
        }
    }
    
    /**
     * 切换活跃 Vault
     */
    fun setActiveVault(vault: BitwardenVault) {
        repository.setActiveVault(vault.id)
        _activeVault.value = vault
        val activeUnlockState = currentUnlockState(vault.id)
        _unlockState.value = activeUnlockState

        if (activeUnlockState == UnlockState.Unlocked) {
            viewModelScope.launch {
                _sendState.value = SendState.Idle
                loadVaultData(vault.id)
                maybeTriggerSilentAutoSync(vault, trigger = "setActiveVault")
            }
        } else {
            clearActiveVaultContent(sendState = SendState.Locked)
        }
    }

    fun isVaultUnlocked(vaultId: Long): Boolean {
        return repository.isVaultUnlocked(vaultId)
    }
    
    /**
     * 同步
     */
    fun sync() {
        val vault = _activeVault.value ?: return
        
        if (!repository.isVaultUnlocked(vault.id)) {
            viewModelScope.launch {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_unlock_vault_first)))
            }
            return
        }

        requestSyncWithStartupGrace(
            vaultId = vault.id,
            reason = SyncTriggerReason.MANUAL,
            force = true
        )
    }

    fun syncUnlockedVaults() {
        val unlockedVaultIds = collectUnlockedVaultIds()
        if (unlockedVaultIds.isEmpty()) {
            viewModelScope.launch {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_unlock_any_vault)))
            }
            return
        }

        val visibleVaultId = _activeVault.value?.id?.takeIf { it in unlockedVaultIds } ?: unlockedVaultIds.first()
        unlockedVaultIds.forEach { vaultId ->
            requestSyncWithStartupGrace(
                vaultId = vaultId,
                reason = if (vaultId == visibleVaultId) {
                    SyncTriggerReason.MANUAL
                } else {
                    SyncTriggerReason.PAGE_ENTER
                },
                force = true
            )
        }
    }

    /**
     * 页面持有整个等待期，离开页面时取消尚未发出的同步请求。
     * 只接受页面明确选中的 Vault，不回退到全局活跃账号。
     */
    fun beginPageEnterAutoSync(vaultId: Long): Long {
        return pageAutoSyncScheduler.begin(
            initialDelayMs = pageAutoSyncDelayMs(),
            reason = SyncTriggerReason.PAGE_ENTER
        ) {
            if (_isAutoSyncEnabled.value) listOf(vaultId) else emptyList()
        }
    }

    /**
     * ALL 视图持有的后台同步会话。
     *
     * 调用方必须在离开对应 ALL 视图时使用返回的 sessionId 调用
     * [endPageAutoSync]。旧页面的 sessionId 无法取消新页面建立的会话。
     */
    fun beginAllViewAutoSync(): Long {
        return pageAutoSyncScheduler.begin(
            initialDelayMs = pageAutoSyncDelayMs(),
            reason = SyncTriggerReason.PERIODIC
        ) {
            if (!_isAutoSyncEnabled.value) {
                emptyList()
            } else {
                BitwardenAutoSyncTargetPlanner.allViewTargets(
                    unlockedVaultIds = awaitUnlockedVaultIds(),
                    activeVaultId = _activeVault.value?.id
                )
            }
        }
    }

    fun endPageAutoSync(sessionId: Long) {
        pageAutoSyncScheduler.end(sessionId)
    }

    private fun pageAutoSyncDelayMs(): Long {
        val elapsed = System.currentTimeMillis() - processStartMs
        val coldStartRemaining = (COLD_START_AUTO_SYNC_GRACE_MS - elapsed).coerceAtLeast(0L)
        return maxOf(BitwardenPageAutoSyncScheduler.PAGE_ENTER_AUTO_SYNC_DELAY_MS, coldStartRemaining)
    }

    /**
     * 本地数据增删改后触发自动同步（带防抖）。
     */
    fun requestLocalMutationSync(vaultId: Long? = null) {
        val targetVaultId = vaultId ?: _activeVault.value?.id ?: return
        syncOrchestrator.requestSync(
            vaultId = targetVaultId,
            reason = SyncTriggerReason.LOCAL_MUTATION
        )
    }

    /**
     * 指定 vault 的手动同步请求（用于页面顶部“一键同步”按钮）。
     */
    internal fun requestNotificationSync(vaultId: Long, isStillValid: () -> Boolean) {
        if (!isStillValid() || !takagi.ru.monica.security.SessionManager.isUnlocked.value ||
            !repository.isAutoSyncEnabled || !repository.isVaultUnlocked(vaultId)) return
        requestSyncWithStartupGrace(vaultId, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = isStillValid)
    }

    fun requestManualSync(vaultId: Long) {
        requestSyncWithStartupGrace(
            vaultId = vaultId,
            reason = SyncTriggerReason.MANUAL,
            force = true
        )
    }

    /**
     * 加载 Send 列表。
     *
     * 多账号视图改造后：本方法默认刷新当前活跃 Vault 的 Send；与此同时也会刷新跨账号
     * 视图（[sendsAcrossVaults]）以保持两侧一致。如果当前没有活跃 Vault，跨账号视图
     * 仍会从所有已解锁账号读取，以满足"用户切走当前活跃 vault 但仍要看到所有 send"的
     * 需求。
     */
    fun loadSends(forceRemoteSync: Boolean = false) {
        val vault = _activeVault.value
        viewModelScope.launch {
            // 跨账号视图先刷新一次（即便没有活跃 Vault 也能展示其它已解锁账号下的 Send）
            refreshSendsAcrossVaults()

            if (vault == null) {
                _sends.value = emptyList()
                if (_sendsAcrossVaults.value.isEmpty()) {
                    _sendState.value = SendState.Error(strings.get(R.string.legacy_ui_connect_bitwarden_first))
                } else {
                    _sendState.value = SendState.Idle
                }
                return@launch
            }
            if (!repository.isVaultUnlocked(vault.id)) {
                _sendState.value = SendState.Locked
                return@launch
            }

            if (!forceRemoteSync) {
                _sendState.value = SendState.Loading
                _sends.value = repository.getSends(vault.id)
                refreshSendsAcrossVaults()
                _sendState.value = SendState.Idle
                return@launch
            }

            _sendState.value = SendState.Syncing
            when (val result = refreshSendsViaCoordinator(vault.id)) {
                is BitwardenRepository.SendSyncResult.Success -> {
                    _sends.value = result.sends
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Idle
                }
                is BitwardenRepository.SendSyncResult.Warning -> {
                    _sends.value = result.sends
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Warning(result.message)
                }
                is BitwardenRepository.SendSyncResult.Error -> {
                    _sends.value = repository.getSends(vault.id)
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Error(result.message)
                }
            }
        }
    }

    /**
     * 强制刷新所有已解锁 Vault 的 Send。供 Send 标签页"全量下拉同步"使用。
     */
    fun refreshAllUnlockedSends() {
        val vaults = _vaults.value
        val unlockedVaultIds = _unlockStateByVault.value
            .filterValues { it == UnlockState.Unlocked }
            .keys
        if (unlockedVaultIds.isEmpty()) {
            return
        }
        viewModelScope.launch {
            _sendState.value = SendState.Syncing
            var anyError: String? = null
            var anyWarning: String? = null
            unlockedVaultIds.forEach { vaultId ->
                val vault = vaults.firstOrNull { it.id == vaultId } ?: return@forEach
                when (val result = refreshSendsViaCoordinator(vault.id)) {
                    is BitwardenRepository.SendSyncResult.Success -> Unit
                    is BitwardenRepository.SendSyncResult.Warning -> {
                        if (anyWarning == null) anyWarning = result.message
                    }
                    is BitwardenRepository.SendSyncResult.Error -> {
                        if (anyError == null) anyError = result.message
                    }
                }
            }
            // 同步后刷新本地状态
            _activeVault.value?.let { active ->
                if (repository.isVaultUnlocked(active.id)) {
                    _sends.value = repository.getSends(active.id)
                }
            }
            refreshSendsAcrossVaults()
            _sendState.value = when {
                anyError != null -> SendState.Error(anyError!!)
                anyWarning != null -> SendState.Warning(anyWarning!!)
                else -> SendState.Idle
            }
        }
    }

    private suspend fun refreshSendsViaCoordinator(vaultId: Long): BitwardenRepository.SendSyncResult {
        return when (val coordinatedResult = repository.syncViaCoordinator(
            vaultId = vaultId,
            requestIdPrefix = "bw-send-vault",
            trigger = SyncTrigger.MANUAL,
            priority = SyncPriority.MANUAL,
            mode = SyncMode.FOREGROUND,
            networkPolicy = if (repository.isSyncOnWifiOnly) {
                SyncNetworkPolicy.WIFI_ONLY
            } else {
                SyncNetworkPolicy.REQUIRED
            }
        )) {
            is BitwardenCoordinatedSyncResult.Completed -> when (val result = coordinatedResult.result) {
                is BitwardenRepository.SyncResult.Success -> {
                    BitwardenRepository.SendSyncResult.Success(repository.getSends(vaultId))
                }
                is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                    BitwardenRepository.SendSyncResult.Warning(
                        sends = repository.getSends(vaultId),
                        message = result.reason
                    )
                }
                is BitwardenRepository.SyncResult.Error -> {
                    BitwardenRepository.SendSyncResult.Error(result.message)
                }
            }
            BitwardenCoordinatedSyncResult.Merged,
            is BitwardenCoordinatedSyncResult.Skipped -> {
                BitwardenRepository.SendSyncResult.Success(repository.getSends(vaultId))
            }
            is BitwardenCoordinatedSyncResult.Blocked -> {
                BitwardenRepository.SendSyncResult.Error(
                    coordinatedResult.error.redactedMessage ?: coordinatedResult.error.kind.name
                )
            }
            is BitwardenCoordinatedSyncResult.Canceled -> {
                BitwardenRepository.SendSyncResult.Error(coordinatedResult.reason ?: strings.get(R.string.legacy_ui_sync_cancelled))
            }
            is BitwardenCoordinatedSyncResult.Failed -> {
                BitwardenRepository.SendSyncResult.Error(coordinatedResult.error.message ?: strings.get(R.string.bitwarden_message_sync_failed))
            }
        }
    }

    /**
     * 创建文本 Send
     */
    fun saveSendDraft(
        vaultId: Long,
        draft: takagi.ru.monica.bitwarden.service.BitwardenSendDraft,
        existing: BitwardenSend? = null,
        fileUri: Uri? = null,
        fileName: String? = null
    ) {
        if (existing != null && existing.vaultId != vaultId) {
            _sendState.value = SendState.Error(strings.get(R.string.send_edit_failed))
            return
        }
        val vault = resolveVault(vaultId) ?: return
        if (!repository.isVaultUnlocked(vault.id)) {
            _sendState.value = SendState.Locked
            return
        }
        viewModelScope.launch {
            _sendState.value = SendState.Creating
            try {
                draft.validate(System.currentTimeMillis(), existing?.isTextType ?: (fileUri == null))
            } catch (_: IllegalArgumentException) {
                _sendState.value = SendState.Error(strings.get(R.string.send_edit_invalid))
                return@launch
            }
            val result = when {
                existing != null -> {
                    repository.updateSend(existing, draft)
                }
                fileUri != null -> repository.createFileSend(vault.id, draft.title, fileUri,
                    fileName.orEmpty(), draft.notes, draft.password, draft.maxAccessCount,
                    draft.hideEmail, draft.deletionMillis, draft.expirationMillis,
                    authAction = draft.authAction, recipientEmails = draft.recipientEmails, disabled = draft.disabled)
                else -> repository.createTextSend(vault.id, draft.title, draft.text,
                    draft.notes, draft.password, draft.maxAccessCount, draft.hideEmail,
                    draft.hiddenText, draft.deletionMillis, draft.expirationMillis,
                    authAction = draft.authAction, recipientEmails = draft.recipientEmails, disabled = draft.disabled)
            }
            when (result) {
                is BitwardenRepository.SendMutationResult.Success -> {
                    if (!repository.isVaultUnlocked(vault.id)) {
                        _sendState.value = SendState.Locked
                        return@launch
                    }
                    if (_activeVault.value?.id == vault.id) {
                        _sends.value = listOf(result.send) + _sends.value.filterNot {
                            it.vaultId == vault.id && it.bitwardenSendId == result.send.bitwardenSendId
                        }
                    }
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Idle
                    _sendCreateSuccessVersion.value += 1
                    requestLocalMutationSync(vault.id)
                    _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.send_saved)))
                }
                is BitwardenRepository.SendMutationResult.Error -> {
                    _sendState.value = SendState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
                is BitwardenRepository.SendMutationResult.Deleted -> _sendState.value = SendState.Idle
            }
        }
    }

    fun createTextSend(
        vaultId: Long? = null,
        title: String,
        text: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        hiddenText: Boolean,
        expireInDays: Int
    ) {
        val vault = vaultId?.let(::resolveVault) ?: _activeVault.value ?: return
        if (!repository.isVaultUnlocked(vault.id)) {
            _sendState.value = SendState.Locked
            return
        }

        viewModelScope.launch {
            _sendState.value = SendState.Creating
            val now = System.currentTimeMillis()
            val days = expireInDays.coerceIn(1, 30)
            val expirationMillis = now + days * 24L * 60L * 60L * 1000L
            val deletionMillis = now + (days + 1).coerceAtMost(31) * 24L * 60L * 60L * 1000L

            when (
                val result = repository.createTextSend(
                    vaultId = vault.id,
                    title = title.trim(),
                    text = text.trim(),
                    notes = notes?.trim(),
                    password = password?.trim(),
                    maxAccessCount = maxAccessCount,
                    hideEmail = hideEmail,
                    hiddenText = hiddenText,
                    deletionMillis = deletionMillis,
                    expirationMillis = expirationMillis
                )
            ) {
                is BitwardenRepository.SendMutationResult.Success -> {
                    if (_activeVault.value?.id == vault.id) {
                        _sends.value = listOf(result.send) + _sends.value.filterNot {
                            it.bitwardenSendId == result.send.bitwardenSendId
                        }
                    }
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Idle
                    _sendCreateSuccessVersion.value = _sendCreateSuccessVersion.value + 1
                    logBitwardenSendCreate(vault.id, result.send)
                    requestLocalMutationSync(vault.id)
                    _events.emit(BitwardenEvent.SendCreated(strings.get(R.string.legacy_ui_send_created)))
                }
                is BitwardenRepository.SendMutationResult.Error -> {
                    _sendState.value = SendState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
                is BitwardenRepository.SendMutationResult.Deleted -> {
                    _sendState.value = SendState.Idle
                }
            }
        }
    }

    fun createFileSend(
        vaultId: Long? = null,
        title: String,
        fileUri: Uri,
        fileName: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        expireInDays: Int
    ) {
        val vault = vaultId?.let(::resolveVault) ?: _activeVault.value ?: return
        if (!repository.isVaultUnlocked(vault.id)) {
            _sendState.value = SendState.Locked
            return
        }

        viewModelScope.launch {
            _sendState.value = SendState.Creating
            val now = System.currentTimeMillis()
            val days = expireInDays.coerceIn(1, 30)
            val expirationMillis = now + days * 24L * 60L * 60L * 1000L
            val deletionMillis = now + (days + 1).coerceAtMost(31) * 24L * 60L * 60L * 1000L

            when (
                val result = repository.createFileSend(
                    vaultId = vault.id,
                    title = title.trim(),
                    fileUri = fileUri,
                    fileName = fileName.trim(),
                    notes = notes?.trim(),
                    password = password?.trim(),
                    maxAccessCount = maxAccessCount,
                    hideEmail = hideEmail,
                    deletionMillis = deletionMillis,
                    expirationMillis = expirationMillis
                )
            ) {
                is BitwardenRepository.SendMutationResult.Success -> {
                    if (_activeVault.value?.id == vault.id) {
                        _sends.value = listOf(result.send) + _sends.value.filterNot {
                            it.bitwardenSendId == result.send.bitwardenSendId
                        }
                    }
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Idle
                    _sendCreateSuccessVersion.value = _sendCreateSuccessVersion.value + 1
                    logBitwardenSendCreate(vault.id, result.send)
                    requestLocalMutationSync(vault.id)
                    _events.emit(BitwardenEvent.SendCreated(strings.get(R.string.legacy_ui_file_send_created)))
                }
                is BitwardenRepository.SendMutationResult.Error -> {
                    _sendState.value = SendState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
                is BitwardenRepository.SendMutationResult.Deleted -> {
                    _sendState.value = SendState.Idle
                }
            }
        }
    }

    /**
     * 删除 Send。
     *
     * 支持跨 Vault 场景：[vaultId] 不传时回退到 [_activeVault]，但跨 vault 删除时
     * 调用方应显式传入 send 所属的 vault id（来自 [BitwardenSend.vaultId]），否则
     * 在多账号视图里对非活跃 Vault 的 Send 调用 deleteSend 会落到错误的 vault。
     */
    fun deleteSend(sendId: String, vaultId: Long? = null) {
        val vault = vaultId?.let(::resolveVault) ?: _activeVault.value ?: return
        if (!repository.isVaultUnlocked(vault.id)) {
            _sendState.value = SendState.Locked
            return
        }

        viewModelScope.launch {
            val deletingSend = _sendsAcrossVaults.value.firstOrNull {
                it.vaultId == vault.id && it.bitwardenSendId == sendId
            } ?: _sends.value.firstOrNull { it.bitwardenSendId == sendId }
            _sendState.value = SendState.Deleting
            when (val result = repository.deleteSend(vault.id, sendId)) {
                is BitwardenRepository.SendMutationResult.Deleted -> {
                    if (_activeVault.value?.id == vault.id) {
                        _sends.value = _sends.value.filterNot { it.bitwardenSendId == result.sendId }
                    }
                    refreshSendsAcrossVaults()
                    _sendState.value = SendState.Idle
                    logBitwardenSendDelete(
                        vaultId = vault.id,
                        sendId = result.sendId,
                        sendName = deletingSend?.name
                    )
                    requestLocalMutationSync(vault.id)
                    _events.emit(BitwardenEvent.SendDeleted(strings.get(R.string.legacy_ui_send_deleted)))
                }
                is BitwardenRepository.SendMutationResult.Error -> {
                    _sendState.value = SendState.Error(result.message)
                    _events.emit(BitwardenEvent.ShowError(result.message))
                }
                is BitwardenRepository.SendMutationResult.Success -> {
                    _sendState.value = SendState.Idle
                }
            }
        }
    }
    
    /**
     * 搜索
     */
    fun search(query: String) {
        val vault = _activeVault.value ?: return
        
        viewModelScope.launch {
            if (query.isBlank()) {
                _searchResults.value = emptyList()
            } else {
                _searchResults.value = repository.searchEntries(vault.id, query)
            }
        }
    }
    
    /**
     * 清除搜索
     */
    fun clearSearch() {
        _searchResults.value = emptyList()
    }
    
    /**
     * 选择文件夹
     */
    fun selectFolder(folder: BitwardenFolder?) {
        _selectedFolder.value = folder
        
        viewModelScope.launch {
            if (folder == null) {
                val vault = _activeVault.value ?: return@launch
                _entries.value = repository.getPasswordEntries(vault.id)
            } else {
                _entries.value = repository.getPasswordEntriesByFolder(folder.vaultId, folder.bitwardenFolderId)
            }
        }
    }
    
    /**
     * 解决冲突：使用本地版本
     */
    fun resolveConflictWithLocal(conflictId: Long) {
        viewModelScope.launch {
            val conflictSnapshot = _conflicts.value.firstOrNull { it.id == conflictId }
            val success = repository.resolveConflictWithLocal(conflictId)
            if (success) {
                logBitwardenConflictResolved(conflictSnapshot, "保留本地版本")
                loadConflicts()
                _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_conflict_resolved_local)))
            } else {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_conflict_resolve_failed)))
            }
        }
    }
    
    /**
     * 解决冲突：使用服务器版本
     */
    fun resolveConflictWithServer(conflictId: Long) {
        viewModelScope.launch {
            val conflictSnapshot = _conflicts.value.firstOrNull { it.id == conflictId }
            val success = repository.resolveConflictWithServer(conflictId)
            if (success) {
                logBitwardenConflictResolved(conflictSnapshot, "使用服务器版本")
                loadConflicts()
                sync() // 重新同步以获取服务器版本
                _events.emit(BitwardenEvent.ShowSuccess(strings.get(R.string.legacy_ui_conflict_resolved_remote)))
            } else {
                _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_conflict_resolve_failed)))
            }
        }
    }
    
    /**
     * 重置登录状态
     */
    fun resetLoginState(cancelInFlight: Boolean = false) {
        if (_loginState.value is LoginState.Loading && !cancelInFlight) return
        authenticationJob?.cancel()
        authenticationJob = null
        twoFactorState?.clear()
        twoFactorState = null
        pendingServerUrl = null
        pendingTlsConfig = null
        _loginState.value = LoginState.Idle
    }
    
    // ==================== 设置相关 ====================
    
    var isAutoSyncEnabled: Boolean
        get() = _isAutoSyncEnabled.value
        set(value) {
            repository.isAutoSyncEnabled = value
            _isAutoSyncEnabled.value = value
            if (!value) {
                repository.isSyncOnWifiOnly = false
                _isSyncOnWifiOnly.value = false
            }
        }
    
    var isSyncOnWifiOnly: Boolean
        get() = _isSyncOnWifiOnly.value
        set(value) {
            repository.isSyncOnWifiOnly = value
            _isSyncOnWifiOnly.value = value
        }
    
    /**
     * 是否永不锁定 Bitwarden
     */
    var isNeverLockEnabled: Boolean
        get() = _isNeverLockEnabled.value
        set(value) { 
            repository.isNeverLockEnabled = value
            _isNeverLockEnabled.value = value
        }
    
    val lastSyncTime: Long
        get() = repository.lastSyncTime
    
    // 同步队列计数（实时）
    val pendingSyncCount: StateFlow<Int> = repository.getPendingSyncCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedSyncCount: StateFlow<Int> = repository.getFailedSyncCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    
    // ==================== 私有方法 ====================

    private fun resolveVault(vaultId: Long): BitwardenVault? {
        return _vaults.value.firstOrNull { it.id == vaultId }
            ?: _activeVault.value?.takeIf { it.id == vaultId }
    }

    private fun currentRepositoryUnlockState(vaultId: Long): UnlockState {
        return if (repository.isVaultUnlocked(vaultId)) {
            UnlockState.Unlocked
        } else {
            UnlockState.Locked
        }
    }

    private fun currentUnlockState(vaultId: Long): UnlockState {
        return _unlockStateByVault.value[vaultId] ?: currentRepositoryUnlockState(vaultId)
    }

    private fun setUnlockState(vaultId: Long, state: UnlockState) {
        val updated = _unlockStateByVault.value.toMutableMap()
        val previous = updated[vaultId]
        updated[vaultId] = state
        _unlockStateByVault.value = updated
        // 解锁状态变化（解锁 / 锁定）会改变跨账号 Send 视图能看到的范围
        if (previous != state && (state == UnlockState.Unlocked || previous == UnlockState.Unlocked)) {
            viewModelScope.launch { refreshSendsAcrossVaults() }
        }
    }

    private fun removeUnlockState(vaultId: Long) {
        val updated = _unlockStateByVault.value.toMutableMap()
        val previous = updated.remove(vaultId)
        _unlockStateByVault.value = updated
        if (previous == UnlockState.Unlocked) {
            viewModelScope.launch { refreshSendsAcrossVaults() }
        }
    }

    private fun replaceUnlockStates(vaults: List<BitwardenVault>) {
        _unlockStateByVault.value = vaults.associate { vault ->
            vault.id to currentRepositoryUnlockState(vault.id)
        }
        viewModelScope.launch { refreshSendsAcrossVaults() }
    }

    private fun collectUnlockedVaultIds(vaults: List<BitwardenVault> = _vaults.value): List<Long> {
        return vaults
            .asSequence()
            .map { it.id }
            .filter(repository::isVaultUnlocked)
            .toList()
    }

    private suspend fun awaitUnlockedVaultIds(): List<Long> {
        var unlockedVaultIds = collectUnlockedVaultIds()
        if (unlockedVaultIds.isNotEmpty()) return unlockedVaultIds

        delay(STARTUP_AUTO_SYNC_UNLOCK_WAIT_MS)
        runCatching {
            if (_vaults.value.isEmpty()) {
                refreshVaultListSnapshot()
            } else {
                replaceUnlockStates(_vaults.value)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to refresh unlocked vaults for auto sync", error)
        }
        unlockedVaultIds = collectUnlockedVaultIds()
        return unlockedVaultIds
    }

    private fun observeVaultSnapshots() {
        viewModelScope.launch {
            repository.getAllVaultsFlow().collect { vaultList ->
                val previousActiveVaultId = _activeVault.value?.id
                _vaults.value = vaultList
                replaceUnlockStates(vaultList)

                val refreshedActive = when {
                    previousActiveVaultId != null -> {
                        vaultList.firstOrNull { it.id == previousActiveVaultId }
                            ?: repository.getActiveVault()
                    }
                    else -> repository.getActiveVault()
                }

                _activeVault.value = refreshedActive
                val activeUnlockState = refreshedActive?.let { currentUnlockState(it.id) } ?: UnlockState.Locked
                _unlockState.value = activeUnlockState

                if (previousActiveVaultId != null &&
                    vaultList.none { it.id == previousActiveVaultId } &&
                    (refreshedActive == null || activeUnlockState != UnlockState.Unlocked)
                ) {
                    clearActiveVaultContent(
                        sendState = if (refreshedActive == null) SendState.Idle else SendState.Locked
                    )
                }
            }
        }
    }

    private suspend fun refreshVaultListSnapshot(preferredActiveVaultId: Long? = _activeVault.value?.id) {
        val vaultList = repository.getAllVaults()
        _vaults.value = vaultList
        replaceUnlockStates(vaultList)

        val refreshedActive = preferredActiveVaultId
            ?.let { activeVaultId -> vaultList.firstOrNull { it.id == activeVaultId } }
            ?: repository.getActiveVault()
        _activeVault.value = refreshedActive
        _unlockState.value = refreshedActive?.let { currentUnlockState(it.id) } ?: UnlockState.Locked
    }

    private fun clearActiveVaultContent(sendState: SendState) {
        _entries.value = emptyList()
        _folders.value = emptyList()
        _sends.value = emptyList()
        _sendState.value = sendState
        // 当前 Vault 的内容被清空时，跨账号 Send 视图仍可能含有其它已解锁账号的 Send，
        // 因此刷新而非清空。
        viewModelScope.launch { refreshSendsAcrossVaults() }
    }

    private fun logBitwardenSendCreate(vaultId: Long, send: BitwardenSend) {
        OperationLogger.logCreate(
            itemType = OperationLogItemType.BITWARDEN_SEND,
            itemId = buildBitwardenItemId(vaultId, "send:${send.bitwardenSendId}"),
            itemTitle = send.name.ifBlank { "Send" },
            details = listOf(
                FieldChange("Vault", "", "Bitwarden #$vaultId"),
                FieldChange("类型", "", if (send.isTextType) "文本 Send" else "文件 Send")
            )
        )
    }

    private fun logBitwardenSendDelete(vaultId: Long, sendId: String, sendName: String?) {
        OperationLogger.logDelete(
            itemType = OperationLogItemType.BITWARDEN_SEND,
            itemId = buildBitwardenItemId(vaultId, "send:$sendId"),
            itemTitle = sendName?.ifBlank { "Send" } ?: "Send",
            detail = "从 Vault #$vaultId 删除"
        )
    }

    private fun logBitwardenSyncSummary(
        vaultId: Long,
        appliedChangeCount: Int,
        availableOfflineCount: Int,
        remoteAddedCount: Int,
        remoteUpdatedCount: Int,
        uploadedCount: Int,
        deletedCount: Int,
        conflictCount: Int,
        uploadFailedCount: Int,
        skippedDueToLocalDirtyCount: Int,
        silent: Boolean
    ) {
        OperationLogger.logSync(
            itemType = OperationLogItemType.BITWARDEN_SYNC,
            itemId = System.currentTimeMillis(),
            itemTitle = "Bitwarden Vault #$vaultId",
            details = listOf(
                FieldChange("模式", "", if (silent) "静默同步" else "手动同步"),
                FieldChange("本次变更", "", appliedChangeCount.toString()),
                FieldChange("离线可查看总数", "", availableOfflineCount.toString()),
                FieldChange("远端新增", "", remoteAddedCount.toString()),
                FieldChange("远端更新", "", remoteUpdatedCount.toString()),
                FieldChange("本地上传", "", uploadedCount.toString()),
                FieldChange("删除处理", "", deletedCount.toString()),
                FieldChange("冲突数", "", conflictCount.toString()),
                FieldChange("上传失败", "", uploadFailedCount.toString()),
                FieldChange("本地待上传阻塞", "", skippedDueToLocalDirtyCount.toString())
            )
        )
    }

    private fun logBitwardenConflictDetected(vaultId: Long, conflictCount: Int) {
        OperationLogger.logCreate(
            itemType = OperationLogItemType.BITWARDEN_CONFLICT,
            itemId = System.currentTimeMillis(),
            itemTitle = "Bitwarden Vault #$vaultId",
            details = listOf(
                FieldChange("状态", "", "检测到同步冲突"),
                FieldChange("冲突数", "", conflictCount.toString())
            )
        )
    }

    private fun logBitwardenConflictResolved(
        conflict: BitwardenConflictBackup?,
        resolutionLabel: String
    ) {
        val vaultId = conflict?.vaultId ?: (_activeVault.value?.id ?: 0L)
        val conflictKey = conflict?.bitwardenCipherId
            ?: "conflict-${conflict?.id ?: System.currentTimeMillis()}"
        OperationLogger.logUpdate(
            itemType = OperationLogItemType.BITWARDEN_CONFLICT,
            itemId = buildBitwardenItemId(vaultId, conflictKey),
            itemTitle = conflict?.entryTitle?.ifBlank { "Bitwarden 冲突" } ?: "Bitwarden 冲突",
            changes = listOf(
                FieldChange("处理状态", "待处理", "已解决"),
                FieldChange("解决策略", "", resolutionLabel)
            )
        )
    }

    private fun buildBitwardenItemId(vaultId: Long, key: String): Long {
        return "${vaultId}:$key".hashCode().toLong() and 0x7FFFFFFFL
    }
    
    private suspend fun loadVaultData(vaultId: Long) {
        try {
            _entries.value = repository.getPasswordEntries(vaultId)
            _folders.value = repository.getFolders(vaultId)
            _sends.value = repository.getSends(vaultId)
            refreshSendsAcrossVaults()
            loadConflicts()
        } catch (e: Exception) {
            Log.e(TAG, "加载 Vault 数据失败", e)
            _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_data_load_failed, e.message ?: "")))
        }
    }

    /**
     * 重新拉取所有已解锁 Vault 下的 Send，合并到 [sendsAcrossVaults]。
     *
     * 多账号场景下，Send 标签页展示来自任意已解锁账号的 Send，因此这个集合
     * 不受 [activeVault] 限制。任何会改变本地 Send 库的入口（创建 / 删除 /
     * sync 完成 / 解锁 / 锁定）都需要触发一次刷新。
     */
    private suspend fun refreshSendsAcrossVaults() {
        val unlockedVaultIds = _unlockStateByVault.value
            .filterValues { it == UnlockState.Unlocked }
            .keys
            .toList()
        _sendsAcrossVaults.value = if (unlockedVaultIds.isEmpty()) {
            emptyList()
        } else {
            repository.getSendsForVaults(unlockedVaultIds)
                .sortedByDescending { it.updatedAt }
        }
    }
    
    private suspend fun loadConflicts() {
        val vault = _activeVault.value ?: return
        _conflicts.value = repository.getConflictBackups(vault.id)
    }

    private fun maybeTriggerSilentAutoSync(vault: BitwardenVault, trigger: String) {
        val reason = when (trigger) {
            "unlock" -> SyncTriggerReason.APP_RESUME
            else -> SyncTriggerReason.PAGE_ENTER
        }
        Log.d(TAG, "Trigger auto sync: vault=${vault.id}, reason=$trigger")
        requestAutoSyncWithStartupGrace(vault.id, reason)
    }

    private fun requestAutoSyncWithStartupGrace(vaultId: Long, reason: SyncTriggerReason) {
        requestSyncWithStartupGrace(vaultId = vaultId, reason = reason)
    }

    private fun requestSyncWithStartupGrace(
        vaultId: Long,
        reason: SyncTriggerReason,
        force: Boolean = false,
        extraDelayMs: Long = 0L,
        isStillValid: (() -> Boolean)? = null,
    ) {
        if (force || reason == SyncTriggerReason.MANUAL) {
            syncOrchestrator.requestSync(vaultId, reason, force = true, isStillValid = isStillValid)
            return
        }
        val elapsed = System.currentTimeMillis() - processStartMs
        val coldStartRemaining = (COLD_START_AUTO_SYNC_GRACE_MS - elapsed).coerceAtLeast(0L)
        val delayMs = coldStartRemaining + extraDelayMs.coerceAtLeast(0L)
        if (delayMs <= 0L) {
            syncOrchestrator.requestSync(vaultId, reason, force = force, isStillValid = isStillValid)
            return
        }
        syncOrchestrator.requestSyncWithDelay(
            vaultId = vaultId,
            reason = reason,
            delayMs = delayMs,
            force = force,
            isStillValid = isStillValid,
        )
    }

    private fun evaluateNetworkGate(): NetworkGateResult {
        val connectivityManager = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return NetworkGateResult.NETWORK_UNAVAILABLE
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkGateResult.NETWORK_UNAVAILABLE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkGateResult.NETWORK_UNAVAILABLE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkGateResult.NETWORK_UNAVAILABLE
        }
        if (repository.isSyncOnWifiOnly &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) {
            return NetworkGateResult.WIFI_REQUIRED
        }
        return NetworkGateResult.ALLOWED
    }

    private suspend fun runSync(vaultId: Long, silent: Boolean): SyncExecutionOutcome {
        val vault = _vaults.value.firstOrNull { it.id == vaultId }
            ?: repository.getAllVaults().firstOrNull { it.id == vaultId }
            ?: return SyncExecutionOutcome.FatalError(strings.get(R.string.bitwarden_message_vault_missing))

        if (!silent) {
            _syncState.value = SyncState.Syncing
        }

        return when (val coordinatedResult = runRepositorySyncThroughCoordinator(vault.id, silent)) {
            BitwardenCoordinatedSyncResult.Merged,
            is BitwardenCoordinatedSyncResult.Skipped -> {
                if (!silent) {
                    _syncState.value = SyncState.Success(
                        appliedChangeCount = 0,
                        availableOfflineCount = 0,
                        conflictCount = 0,
                        uploadFailedCount = 0,
                        skippedDueToLocalDirtyCount = 0
                    )
                }
                SyncExecutionOutcome.Success(
                    appliedChangeCount = 0,
                    availableOfflineCount = 0,
                    conflictCount = 0,
                    uploadFailedCount = 0,
                    skippedDueToLocalDirtyCount = 0
                )
            }

            is BitwardenCoordinatedSyncResult.Blocked -> {
                val message = coordinatedResult.error.redactedMessage ?: coordinatedResult.error.kind.name
                if (!silent) {
                    _syncState.value = SyncState.Error(message)
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_sync_blocked_detail, message)))
                } else {
                    Log.w(TAG, "Silent auto sync blocked: $message")
                }
                SyncExecutionOutcome.Blocked(coordinatedResult.error.toBitwardenBlockReason(), message)
            }

            is BitwardenCoordinatedSyncResult.Canceled -> {
                val message = coordinatedResult.reason ?: strings.get(R.string.legacy_ui_sync_cancelled)
                if (!silent) {
                    _syncState.value = SyncState.Error(message)
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_sync_failed_detail, message)))
                } else {
                    Log.w(TAG, "Silent auto sync canceled: $message")
                }
                classifyError(message)
            }

            is BitwardenCoordinatedSyncResult.Failed -> {
                val message = coordinatedResult.error.message ?: strings.get(R.string.bitwarden_message_sync_failed)
                if (!silent) {
                    _syncState.value = SyncState.Error(message)
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_sync_failed_detail, message)))
                } else {
                    Log.w(TAG, "Silent auto sync failed: $message")
                }
                classifyError(message)
            }

            is BitwardenCoordinatedSyncResult.Completed -> when (val result = coordinatedResult.result) {
            is BitwardenRepository.SyncResult.Success -> {
                val warmedCount = if (silent) {
                    0
                } else {
                    warmBitwardenOfflineSecretCacheForVault(vault.id)
                }
                val offlineReadyCount = maxOf(result.availableOfflineCount, warmedCount)
                val syncSummary = BitwardenSyncSummary(
                    vaultId = vault.id,
                    vaultLabel = vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email,
                    appliedChangeCount = result.appliedChangeCount,
                    offlineReadyCount = offlineReadyCount,
                    conflictCount = result.conflictCount,
                    uploadFailedCount = result.uploadFailedCount,
                    skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount
                )
                logBitwardenSyncSummary(
                    vaultId = vault.id,
                    appliedChangeCount = result.appliedChangeCount,
                    availableOfflineCount = offlineReadyCount,
                    remoteAddedCount = result.remoteAddedCount,
                    remoteUpdatedCount = result.remoteUpdatedCount,
                    uploadedCount = result.uploadedCount,
                    deletedCount = result.deletedCount,
                    conflictCount = result.conflictCount,
                    uploadFailedCount = result.uploadFailedCount,
                    skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount,
                    silent = silent
                )
                if (result.conflictCount > 0) {
                    logBitwardenConflictDetected(vault.id, result.conflictCount)
                }
                if (!silent) {
                    _syncState.value = SyncState.Success(
                        appliedChangeCount = result.appliedChangeCount,
                        availableOfflineCount = offlineReadyCount,
                        conflictCount = result.conflictCount,
                        uploadFailedCount = result.uploadFailedCount,
                        skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount
                    )
                }
                if (silent) {
                    scheduleSilentPostSyncRefresh(vault.id)
                    scheduleSilentOfflineCacheWarm(vault.id)
                } else {
                    refreshVaultListSnapshot()
                    if (_activeVault.value?.id == vault.id) {
                        loadVaultData(vault.id)
                    } else {
                        // 即使同步的不是当前活跃 Vault，跨账号 Send 视图也要随之刷新。
                        refreshSendsAcrossVaults()
                    }
                }

                if (!silent) {
                    _events.emit(BitwardenEvent.SyncFinished(syncSummary))
                    BitwardenSyncNotificationHelper.showSyncSummary(
                        context = getApplication<Application>().applicationContext,
                        summary = syncSummary
                    )
                    if (result.conflictCount > 0 || result.uploadFailedCount > 0 || result.skippedDueToLocalDirtyCount > 0) {
                        val warningParts = buildList {
                            if (result.conflictCount > 0) add(strings.get(R.string.legacy_ui_sync_conflict_count, result.conflictCount))
                            if (result.uploadFailedCount > 0) add(strings.get(R.string.legacy_ui_sync_upload_failed_count, result.uploadFailedCount))
                            if (result.skippedDueToLocalDirtyCount > 0) add(strings.get(R.string.legacy_ui_sync_dirty_skipped_count, result.skippedDueToLocalDirtyCount))
                        }
                        _events.emit(
                            BitwardenEvent.ShowWarning(
                                strings.get(R.string.legacy_ui_sync_completed_with_warnings, warningParts.joinToString("，"))
                            )
                        )
                    } else {
                        _events.emit(
                            BitwardenEvent.ShowSuccess(
                                strings.get(
                                    R.string.bitwarden_sync_success_with_offline_ready,
                                    result.appliedChangeCount,
                                    syncSummary.offlineReadyCount
                                )
                            )
                        )
                    }
                }
                SyncExecutionOutcome.Success(
                    appliedChangeCount = result.appliedChangeCount,
                    availableOfflineCount = syncSummary.offlineReadyCount,
                    conflictCount = result.conflictCount,
                    uploadFailedCount = result.uploadFailedCount,
                    skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount
                )
            }

            is BitwardenRepository.SyncResult.Error -> {
                if (!silent) {
                    _syncState.value = SyncState.Error(result.message)
                }
                if (!silent) {
                    _events.emit(BitwardenEvent.ShowError(strings.get(R.string.legacy_ui_sync_failed_detail, result.message)))
                } else {
                    Log.w(TAG, "Silent auto sync failed: ${result.message}")
                }
                classifyError(result.message)
            }

            is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                if (!silent) {
                    _syncState.value = SyncState.Error(strings.get(R.string.legacy_ui_empty_vault_protection))
                }
                if (!silent) {
                    _events.emit(BitwardenEvent.ShowWarning(
                        strings.get(R.string.legacy_ui_empty_vault_warning, result.localCount)
                    ))
                } else {
                    Log.w(
                        TAG,
                        "Silent auto sync blocked by empty-vault protection: local=${result.localCount}, server=${result.serverCount}"
                    )
                }
                SyncExecutionOutcome.FatalError(strings.get(R.string.legacy_ui_empty_vault_protection))
            }
        }
        }
    }

    private fun scheduleSilentPostSyncRefresh(vaultId: Long) {
        viewModelScope.launch {
            silentPostSyncRefreshJob?.cancel()
            silentPostSyncRefreshJob = launch {
                delay(SILENT_SYNC_UI_REFRESH_DELAY_MS)
                refreshVaultListSnapshot()
                if (_activeVault.value?.id == vaultId) {
                    loadVaultData(vaultId)
                } else {
                    refreshSendsAcrossVaults()
                }
            }
        }
    }

    private fun scheduleSilentOfflineCacheWarm(vaultId: Long) {
        viewModelScope.launch {
            silentCacheWarmJobs.remove(vaultId)?.cancel()
            val job = launch {
                delay(SILENT_SYNC_CACHE_WARM_DELAY_MS)
                withContext(Dispatchers.IO) {
                    warmBitwardenOfflineSecretCacheForVault(vaultId)
                }
                silentCacheWarmJobs.remove(vaultId)
            }
            silentCacheWarmJobs[vaultId] = job
        }
    }

    private suspend fun runRepositorySyncThroughCoordinator(
        vaultId: Long,
        silent: Boolean
    ): BitwardenCoordinatedSyncResult {
        return repository.syncViaCoordinator(
            vaultId = vaultId,
            requestIdPrefix = "bw-vm-vault",
            // Silent/auto sync stays background-priority so multi-vault startup
            // does not compete with interactive UI work at PAGE_VISIBLE rank.
            trigger = if (silent) SyncTrigger.APP_START else SyncTrigger.MANUAL,
            priority = if (silent) SyncPriority.BACKGROUND else SyncPriority.MANUAL,
            mode = if (silent) SyncMode.SILENT else SyncMode.FOREGROUND,
            networkPolicy = if (repository.isSyncOnWifiOnly) {
                SyncNetworkPolicy.WIFI_ONLY
            } else {
                SyncNetworkPolicy.REQUIRED
            }
        )
    }

    private suspend fun warmBitwardenOfflineSecretCacheForVault(vaultId: Long): Int {
        return runCatching {
            repository.getPasswordEntries(vaultId).fold(0) { warmedCount: Int, entry: PasswordEntry ->
                if (!entry.hasBitwardenCipherBinding() || entry.password.isBlank()) {
                    warmedCount
                } else {
                    val decoded = decodePasswordOrNull(entry.password)
                    if (decoded.isNullOrBlank()) {
                        warmedCount
                    } else {
                        bitwardenOfflineSecretCache.remember(entry, decoded)
                        warmedCount + 1
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Warm Bitwarden offline cache skipped: ${error.message}")
            0
        }
    }

    private fun decodePasswordOrNull(rawPassword: String): String? {
        if (rawPassword.isEmpty()) return ""
        return runCatching {
            var current = rawPassword
            repeat(3) {
                val decrypted = synchronized(decryptLock) {
                    securityManager.decryptData(current)
                }
                if (decrypted == current) return@runCatching current
                current = decrypted
            }
            current
        }.getOrNull()
    }

    private fun classifyError(message: String): SyncExecutionOutcome =
        classifyBitwardenSyncError(message, strings)
    
    // ==================== 状态类型 ====================
    
    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Success(val vault: BitwardenVault) : LoginState()
        data class TwoFactorRequired(val methods: List<Int>) : LoginState()
        data class Error(val message: String) : LoginState()
    }
    
    sealed class UnlockState {
        object Locked : UnlockState()
        object Unlocking : UnlockState()
        object Unlocked : UnlockState()
    }
    
    sealed class SyncState {
        object Idle : SyncState()
        object Syncing : SyncState()
        data class Success(
            val appliedChangeCount: Int,
            val availableOfflineCount: Int,
            val conflictCount: Int,
            val uploadFailedCount: Int,
            val skippedDueToLocalDirtyCount: Int
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    sealed class SendState {
        object Idle : SendState()
        object Loading : SendState()
        object Syncing : SendState()
        object Creating : SendState()
        object Deleting : SendState()
        object Locked : SendState()
        data class Warning(val message: String) : SendState()
        data class Error(val message: String) : SendState()
    }
    
    // ==================== 事件类型 ====================
    
    sealed class BitwardenEvent {
        data class ShowSuccess(val message: String) : BitwardenEvent()
        data class ShowError(val message: String) : BitwardenEvent()
        data class ShowWarning(val message: String) : BitwardenEvent()
        data class SyncFinished(val summary: BitwardenSyncSummary) : BitwardenEvent()
        data class SendCreated(val message: String) : BitwardenEvent()
        data class SendDeleted(val message: String) : BitwardenEvent()
        data class ShowTwoFactorDialog(val methods: List<Int>) : BitwardenEvent()
        data class ShowCaptchaDialog(
            val message: String,
            val forTwoFactor: Boolean,
            val siteKey: String? = null
        ) : BitwardenEvent()
        data class NavigateToVault(val vaultId: Long) : BitwardenEvent()
        object NavigateToLogin : BitwardenEvent()
    }
}

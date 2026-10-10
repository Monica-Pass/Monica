package takagi.ru.monica.viewmodel

import takagi.ru.monica.utils.StringResolver

import takagi.ru.monica.R

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.keepass.KeePassPasskeyDeleteExecutor
import takagi.ru.monica.keepass.KeePassPasskeyUpdateExecutor
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.passkey.PasskeyPageSyncPolicy
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncItemKind
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager

/**
 * Passkey ViewModel
 * 
 * 管理 Passkey 数据和 UI 状态
 */
class PasskeyViewModel internal constructor(
    private val repository: PasskeyRepository,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    securityManager: SecurityManager? = null,
    private val strings: StringResolver
) : ViewModel() {
    private val keepassBridge = if (context != null && localKeePassDatabaseDao != null && securityManager != null) {
        KeePassCompatibilityBridge(
            KeePassWorkspaceRepository(
                context = context.applicationContext,
                dao = localKeePassDatabaseDao,
                securityManager = securityManager
            )
        )
    } else {
        null
    }
    private val keepassPasskeyUpdateExecutor = KeePassPasskeyUpdateExecutor(keepassBridge)
    private val keepassPasskeyDeleteExecutor = KeePassPasskeyDeleteExecutor(keepassBridge)
    
    // ==================== UI 状态 ====================
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val settingsManager = context?.applicationContext?.let(::SettingsManager)
    private val _categoryFilter = MutableStateFlow(SavedCategoryFilterState())
    val categoryFilter: StateFlow<SavedCategoryFilterState> = _categoryFilter.asStateFlow()
    private val _isCategoryFilterReady = MutableStateFlow(settingsManager == null)
    val isCategoryFilterReady: StateFlow<Boolean> = _isCategoryFilterReady.asStateFlow()
    private var hasSelectedCategoryFilter = false
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        settingsManager?.let { manager ->
            viewModelScope.launch {
                runCatching {
                    manager.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.PASSKEY).first()
                }.onSuccess { saved ->
                    // A slow initial read must not replace a newer user selection.
                    if (!hasSelectedCategoryFilter) _categoryFilter.value = saved
                    _isCategoryFilterReady.value = true
                }
            }
        }
        viewModelScope.launch {
            repairLegacyDetachedKeePassPasskeys()
            isCategoryFilterReady.first { it }
            refreshKeePassPasskeys(trigger = "PASSKEY_INIT")
        }
    }
    
    // ==================== 数据流 ====================
    
    /**
     * 所有 Passkey 列表
     */
    val allPasskeys: StateFlow<List<PasskeyEntry>> = repository.getAllPasskeys()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * 搜索结果（根据搜索词过滤）
     */
    val filteredPasskeys: StateFlow<List<PasskeyEntry>> = combine(
        allPasskeys,
        searchQuery
    ) { passkeys, query ->
        if (query.isBlank()) {
            passkeys
        } else {
            passkeys.filter { passkey ->
                passkey.rpId.contains(query, ignoreCase = true) ||
                    passkey.rpName.contains(query, ignoreCase = true) ||
                    passkey.userName.contains(query, ignoreCase = true) ||
                    passkey.userDisplayName.contains(query, ignoreCase = true) ||
                    passkey.notes.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    /**
     * 按域名分组的 Passkey
     */
    val groupedPasskeys: StateFlow<Map<String, List<PasskeyEntry>>> = filteredPasskeys
        .map { passkeys ->
            passkeys.groupBy { it.rpId }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )
    
    /**
     * Passkey 总数
     */
    val passkeyCount: StateFlow<Int> = repository.getPasskeyCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )
    
    // ==================== 设备兼容性检查 ====================
    
    /**
     * 检查设备是否支持完整 Passkey 功能
     * Android 14+ (API 34+) 支持 Credential Provider API
     */
    val isPasskeyFullySupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    
    /**
     * 获取 Android 版本信息
     */
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    
    /**
     * 获取不支持原因（低版本设备）
     */
    val unsupportedReason: String? get() = if (!isPasskeyFullySupported) {
        strings.get(R.string.entry_message_passkey_android_required, androidVersion)
    } else null
    
    // ==================== 操作方法 ====================
    
    /**
     * 更新搜索词
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(filter: SavedCategoryFilterState) {
        hasSelectedCategoryFilter = true
        _categoryFilter.value = filter
        _isCategoryFilterReady.value = true
        val manager = settingsManager ?: return
        // Keep the selection and its pending write alive when the list leaves composition.
        viewModelScope.launch {
            manager.updateCategoryFilterState(SettingsManager.CategoryFilterScope.PASSKEY, filter)
        }
    }
    
    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * 获取指定 Passkey
     */
    suspend fun getPasskeyById(credentialId: String): PasskeyEntry? {
        val passkey = repository.getPasskeyById(credentialId) ?: return null
        return repository.normalizeLegacyDetachedKeePassPasskey(passkey, ::hasKeePassDatabase)
    }

    suspend fun getPasskeyByRecordId(recordId: Long): PasskeyEntry? {
        val passkey = repository.getPasskeyByRecordId(recordId) ?: return null
        return repository.normalizeLegacyDetachedKeePassPasskey(passkey, ::hasKeePassDatabase)
    }
    
    /**
     * 根据域名获取 Passkeys
     */
    fun getPasskeysByRpId(rpId: String): Flow<List<PasskeyEntry>> {
        return repository.getPasskeysByRpId(rpId)
    }

    /**
     * 获取绑定到指定密码的 Passkeys
     */
    fun getPasskeysByBoundPasswordId(passwordId: Long): Flow<List<PasskeyEntry>> {
        return repository.getPasskeysByBoundPasswordId(passwordId)
    }
    
    /**
     * 保存 Passkey
     */
    fun savePasskey(passkey: PasskeyEntry) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val existing = if (passkey.hasPersistentId()) {
                    repository.getPasskeyByRecordId(passkey.id)
                } else {
                    repository.getPasskeyById(passkey.credentialId)
                }
                repository.savePasskey(passkey)
                if (existing == null) {
                    logPasskeyCreate(passkey)
                } else {
                    logPasskeyUpdate(existing, passkey)
                }
            } catch (e: Exception) {
                _errorMessage.value = strings.get(R.string.entry_message_passkey_save_failed, e.message.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 更新 Passkey
     */
    suspend fun copyPasskey(passkey: PasskeyEntry): Result<PasskeyEntry> {
        val copy = passkey.copy(id = 0, boundPasswordId = null, bitwardenCipherId = null,
            syncStatus = if (passkey.bitwardenVaultId == null) "NONE" else "PENDING")
        return try {
            // Treat the destination as new, so the KeePass executor performs conflict checks
            // and cannot enter its source-deletion branch.
            keepassPasskeyUpdateExecutor.update(
                existing = copy.copy(keepassDatabaseId = null), updated = copy,
                persistUpdate = { repository.copyPasskey(it) }
            ).onSuccess { logPasskeyCreate(it) }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.failure(error)
        }
    }

    suspend fun updatePasskey(passkey: PasskeyEntry): Result<PasskeyEntry> {
        val existing = if (passkey.hasPersistentId()) {
            repository.getPasskeyByRecordId(passkey.id)
        } else {
            repository.getPasskeyById(passkey.credentialId)
        }
            ?: return Result.failure(IllegalArgumentException(strings.get(R.string.entry_message_passkey_missing)))
        return try {
            val result = keepassPasskeyUpdateExecutor.update(
                existing = existing,
                updated = passkey,
                persistUpdate = repository::updatePasskey
            )
            result.onSuccess { updatedPasskey ->
                logPasskeyUpdate(existing, updatedPasskey)
            }
            result
        } catch (e: Exception) {
            _errorMessage.value = strings.get(R.string.entry_message_passkey_update_failed, e.message.orEmpty())
            Result.failure(e)
        }
    }

    suspend fun updateMdbxDatabaseForPasskeys(
        recordIds: List<Long>,
        databaseId: Long,
        folderId: String? = null
    ): Result<Unit> {
        return try {
            repository.updateMdbxDatabaseForPasskeys(recordIds, databaseId, folderId)
            Result.success(Unit)
        } catch (e: Exception) {
            _errorMessage.value = strings.get(R.string.entry_message_passkey_ownership_failed, e.message.orEmpty())
            Result.failure(e)
        }
    }

    /**
     * 更新绑定密码
     */
    fun updateBoundPassword(recordId: Long, passwordId: Long?) {
        viewModelScope.launch {
            try {
                val existing = repository.getPasskeyByRecordId(recordId) ?: return@launch
                if (existing.boundPasswordId == passwordId) {
                    return@launch
                }
                repository.updateBoundPasswordId(recordId, passwordId)
                logPasskeyUpdate(existing, existing.copy(boundPasswordId = passwordId))
            } catch (e: Exception) {
                _errorMessage.value = strings.get(R.string.entry_message_passkey_binding_failed, e.message.orEmpty())
            }
        }
    }
    
    /**
     * 更新使用记录
     */
    fun updateUsage(recordId: Long, signCount: Long) {
        viewModelScope.launch {
            try {
                repository.updateUsage(recordId, signCount)
            } catch (e: Exception) {
                _errorMessage.value = strings.get(R.string.entry_message_passkey_usage_failed, e.message.orEmpty())
            }
        }
    }
    
    /**
     * 删除 Passkey
     * 注：PasskeyRepository 会自动处理 Android Keystore 私钥清理
     */
    suspend fun deletePasskey(passkey: PasskeyEntry): Result<Unit> {
        return try {
            val result = keepassPasskeyDeleteExecutor.delete(
                passkey = passkey,
                deleteLocal = repository::deletePasskeyLocalOnly
            )
            if (result.isSuccess) {
                logPasskeyDelete(passkey)
            }
            result
        } catch (e: Exception) {
            _errorMessage.value = strings.get(R.string.entry_message_passkey_delete_failed, e.message.orEmpty())
            Result.failure(e)
        }
    }
    
    /**
     * 根据凭据 ID 删除 Passkey
     * 注：PasskeyRepository 会自动处理 Android Keystore 私钥清理
     */
    fun deletePasskeyByRecordId(recordId: Long) {
        viewModelScope.launch {
            try {
                val passkey = repository.getPasskeyByRecordId(recordId)
                if (passkey != null) {
                    deletePasskey(passkey).getOrThrow()
                } else {
                    repository.deletePasskeyByRecordId(recordId)
                }
            } catch (e: Exception) {
                _errorMessage.value = strings.get(R.string.entry_message_passkey_delete_failed, e.message.orEmpty())
            }
        }
    }
    
    // ==================== Credential Provider 相关 ====================
    
    /**
     * 获取可发现的 Passkeys（用于 Credential Provider 选择界面）
     */
    suspend fun getDiscoverablePasskeys(): List<PasskeyEntry> {
        return repository.getDiscoverablePasskeys()
    }
    
    /**
     * 获取指定域名的可发现 Passkeys
     */
    suspend fun getDiscoverablePasskeysByRpId(rpId: String): List<PasskeyEntry> {
        return repository.getDiscoverablePasskeysByRpId(rpId)
    }

    suspend fun refreshKeePassPasskeys(trigger: String = "PASSKEY_REFRESH") {
        val bridge = keepassBridge ?: return
        val dao = localKeePassDatabaseDao ?: return
        if (!isCategoryFilterReady.value) return
        val filter = categoryFilter.value
        val databaseIds = PasskeyPageSyncPolicy.keePassDatabaseIds(
            filter, withContext(Dispatchers.IO) { dao.getAllDatabasesSync().map { it.id } }
        )
        for (databaseId in databaseIds) {
            // Do not enqueue an empty task for an inaccessible or already indexed database.
            val decision = bridge.legacyProjectionRefreshDecision(
                databaseId, takagi.ru.monica.keepass.KeePassProjectionKind.PASSKEY
            ).getOrNull() ?: continue
            if (!decision.needsRefresh || categoryFilter.value != filter) continue
            requestKeePassPasskeyRefresh(databaseId, trigger)
        }
    }

    private suspend fun requestKeePassPasskeyRefresh(databaseId: Long, trigger: String) {
        SyncTaskRunner.request(
            request = SyncRequest(
                requestId = SyncDiagnostics.nextTaskId("kp-passkey-refresh"),
                target = SyncTarget.KeePassCompatibilityIndex(
                    databaseId = databaseId,
                    itemTypes = setOf(SyncItemKind.PASSKEY)
                ),
                trigger = when (trigger) {
                    "PASSKEY_INIT" -> SyncTrigger.APP_START
                    "PASSKEY_PAGE_ENTER" -> SyncTrigger.PAGE_VISIBLE
                    else -> SyncTrigger.PAGE_VISIBLE
                },
                createdAtMillis = System.currentTimeMillis(),
                priority = SyncPriority.PAGE_VISIBLE,
                mode = SyncMode.SILENT,
                throttleMs = 30_000L
            )
        ) {
            refreshKeePassPasskeysNow(databaseId, trigger)
        }
    }

    private suspend fun refreshKeePassPasskeysNow(databaseId: Long, trigger: String) {
        val taskId = SyncDiagnostics.nextTaskId("kp-passkey")
        val target = "keepass_compat:passkey:$databaseId"
        SyncDiagnostics.queued(taskId, target, trigger)
        val bridge = keepassBridge ?: run {
            SyncDiagnostics.skipped(taskId, target, trigger, "bridge_unavailable")
            return
        }
        val dao = localKeePassDatabaseDao ?: run {
            SyncDiagnostics.skipped(taskId, target, trigger, "dao_unavailable")
            return
        }
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            var databaseCount = 0
            var importedCount = 0
            var failedDatabaseCount = 0
            withContext(Dispatchers.IO) {
                listOfNotNull(dao.getDatabaseById(databaseId)).forEach { database ->
                    databaseCount++
                    val decision = bridge.legacyProjectionRefreshDecision(
                        database.id,
                        takagi.ru.monica.keepass.KeePassProjectionKind.PASSKEY
                    ).getOrElse { error ->
                        failedDatabaseCount++
                        Log.w(TAG, "Failed to inspect KeePass passkey revision for database ${database.id}: ${error.message}")
                        return@forEach
                    }
                    if (!decision.needsRefresh) {
                        return@forEach
                    }
                    bridge.readLegacyPasskeys(database.id)
                        .onSuccess { imported ->
                            importedCount += imported.size
                            repository.syncKeePassPasskeys(database.id, imported)
                            bridge.markLegacyProjectionIndexed(
                                database.id,
                                decision.revisionToken,
                                setOf(takagi.ru.monica.keepass.KeePassProjectionKind.PASSKEY)
                            )
                        }
                        .onFailure { error ->
                            failedDatabaseCount++
                            Log.w(TAG, "Failed to refresh KeePass passkeys for database ${database.id}: ${error.message}")
                        }
                }
            }
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "databases=$databaseCount imported=$importedCount failedDatabases=$failedDatabaseCount"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            throw error
        }
    }

    private suspend fun repairLegacyDetachedKeePassPasskeys() {
        repository.repairLegacyDetachedKeePassPasskeys(::hasKeePassDatabase)
    }

    private suspend fun hasKeePassDatabase(databaseId: Long): Boolean {
        return localKeePassDatabaseDao?.getDatabaseById(databaseId) != null
    }

    private fun logPasskeyCreate(passkey: PasskeyEntry) {
        val details = listOf(
            FieldChange(fieldName = "RpId", oldValue = "", newValue = passkey.rpId),
            FieldChange(fieldName = "Username", oldValue = "", newValue = passkey.userName)
        ).filter { it.newValue.isNotBlank() }

        OperationLogger.logCreate(
            itemType = OperationLogItemType.PASSKEY,
            itemId = passkeyTimelineItemId(passkey),
            itemTitle = passkeyTimelineTitle(passkey),
            details = details
        )
    }

    private fun logPasskeyUpdate(oldPasskey: PasskeyEntry, newPasskey: PasskeyEntry) {
        val changes = mutableListOf<FieldChange>()

        fun addChange(fieldName: String, oldValue: String, newValue: String) {
            if (oldValue != newValue) {
                changes += FieldChange(fieldName = fieldName, oldValue = oldValue, newValue = newValue)
            }
        }

        addChange("RpName", oldPasskey.rpName, newPasskey.rpName)
        addChange("RpId", oldPasskey.rpId, newPasskey.rpId)
        addChange("Username", oldPasskey.userName, newPasskey.userName)
        addChange("DisplayName", oldPasskey.userDisplayName, newPasskey.userDisplayName)
        addChange(
            "BoundPasswordId",
            oldPasskey.boundPasswordId?.toString().orEmpty(),
            newPasskey.boundPasswordId?.toString().orEmpty()
        )
        addChange(
            "CategoryId",
            oldPasskey.categoryId?.toString().orEmpty(),
            newPasskey.categoryId?.toString().orEmpty()
        )
        addChange("SyncStatus", oldPasskey.syncStatus, newPasskey.syncStatus)

        if (changes.isEmpty()) return

        OperationLogger.logUpdate(
            itemType = OperationLogItemType.PASSKEY,
            itemId = passkeyTimelineItemId(newPasskey),
            itemTitle = passkeyTimelineTitle(newPasskey),
            changes = changes
        )
    }

    private fun logPasskeyDelete(passkey: PasskeyEntry) {
        OperationLogger.logDelete(
            itemType = OperationLogItemType.PASSKEY,
            itemId = passkeyTimelineItemId(passkey),
            itemTitle = passkeyTimelineTitle(passkey)
        )
    }

    private fun passkeyTimelineItemId(passkey: PasskeyEntry): Long {
        return passkey.id.takeIf { it > 0L } ?: passkey.credentialId.hashCode().toLong()
    }

    private fun passkeyTimelineTitle(passkey: PasskeyEntry): String {
        return passkey.rpName.ifBlank {
            passkey.rpId.ifBlank { "Passkey" }
        }
    }

    private companion object {
        const val TAG = "PasskeyViewModel"
    }
}

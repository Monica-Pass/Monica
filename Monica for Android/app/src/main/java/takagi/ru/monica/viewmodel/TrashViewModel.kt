package takagi.ru.monica.viewmodel

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.BitwardenRestoreQueueOutcome
import takagi.ru.monica.bitwarden.BitwardenTrashRestoreStateHelper
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.KeePassRestoreTarget
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.utils.SettingsManager
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 回收站中的条目数据类
 */
data class TrashItem(
    val id: Long,
    val title: String,
    val itemType: ItemType,
    val deletedAt: Date,
    val daysRemaining: Int,  // 剩余天数（-1表示不自动清空）
    val originalData: Any  // PasswordEntry 或 SecureItem
)

/**
 * 回收站分类数据类
 */
data class TrashCategory(
    val type: ItemType,
    val displayName: String,
    val count: Int,
    val items: List<TrashItem>
)

internal val TRASH_SECURE_ITEM_TYPES = listOf(
    ItemType.TOTP,
    ItemType.BANK_CARD,
    ItemType.DOCUMENT,
    ItemType.NOTE,
    ItemType.BILLING_ADDRESS,
    ItemType.PAYMENT_ACCOUNT
)

internal fun TrashSettings.shouldAutoCleanup(): Boolean =
    enabled && autoDeleteDays > 0

/**
 * 回收站 ViewModel
 */
class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val strings = AppLocaleStringResolver(application)
    private val database = PasswordDatabase.getDatabase(application)
    private val securityManager = SecurityManager(application)
    private val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(
        context = application.applicationContext,
        database = database,
        securityManager = securityManager
    )
    private val passwordRepository = PasswordRepository(
        passwordEntryDao = database.passwordEntryDao(),
        categoryDao = database.categoryDao(),
        bitwardenFolderDao = database.bitwardenFolderDao(),
        secureItemDao = database.secureItemDao(),
        passkeyDao = database.passkeyDao(),
        passwordArchiveSyncMetaDao = database.passwordArchiveSyncMetaDao(),
        passwordHistoryDao = database.passwordHistoryDao(),
        mdbxRepository = mdbxRepository,
    )
    private val secureItemRepository = SecureItemRepository(
        database.secureItemDao(),
        mdbxRepository,
        securityManager::decryptDataIfMonicaCiphertext,
        takagi.ru.monica.attachments.repository.AttachmentRepository(database.attachmentDao())
    )
    private val bitwardenRepository = BitwardenRepository.getInstance(application)
    private val keepassBridge = KeePassCompatibilityBridge(
        KeePassWorkspaceRepository(application, database.localKeePassDatabaseDao(), securityManager)
    )
    private val settingsManager = SettingsManager(application)
    
    // 回收站设置
    val trashSettings = settingsManager.settingsFlow.map { settings ->
        TrashSettings(
            enabled = settings.trashEnabled,
            autoDeleteDays = settings.trashAutoDeleteDays
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TrashSettings()
    )
    
    init {
        viewModelScope.launch {
            // 直接读取 DataStore，避免使用 stateIn 的默认值抢先执行永久删除。
            val settings = settingsManager.settingsFlow.first()
            cleanupExpiredItemsNow(
                TrashSettings(
                    enabled = settings.trashEnabled,
                    autoDeleteDays = settings.trashAutoDeleteDays
                )
            )
        }
    }
    
    // 已删除的密码条目
    private val deletedPasswords: Flow<List<PasswordEntry>> = 
        database.passwordEntryDao().getDeletedEntries()
    
    // 已删除的安全项目
    private val deletedSecureItems: Flow<List<SecureItem>> = 
        database.secureItemDao().getDeletedItems()
    
    // 合并所有已删除项目并按类型分组
    val trashCategories: StateFlow<List<TrashCategory>> = combine(
        deletedPasswords,
        deletedSecureItems,
        trashSettings
    ) { passwords, secureItems, settings ->
        val now = Date()
        val categories = mutableListOf<TrashCategory>()
        
        // 密码类别
        if (passwords.isNotEmpty()) {
            val passwordItems = passwords.map { entry ->
                val daysRemaining = if (settings.autoDeleteDays > 0 && entry.deletedAt != null) {
                    val daysSinceDelete = TimeUnit.MILLISECONDS.toDays(now.time - entry.deletedAt.time).toInt()
                    maxOf(0, settings.autoDeleteDays - daysSinceDelete)
                } else -1
                
                TrashItem(
                    id = entry.id,
                    title = entry.title,
                    itemType = ItemType.PASSWORD,
                    deletedAt = entry.deletedAt ?: now,
                    daysRemaining = daysRemaining,
                    originalData = entry
                )
            }
            categories.add(TrashCategory(
                type = ItemType.PASSWORD,
                displayName = strings.get(R.string.item_type_password),
                count = passwordItems.size,
                items = passwordItems
            ))
        }
        
        // 所有 SecureItem 类型统一进入回收站，避免新增类型遗漏。
        val secureTypeLabels = mapOf(
            ItemType.TOTP to strings.get(R.string.item_type_authenticator),
            ItemType.BANK_CARD to strings.get(R.string.item_type_bank_card),
            ItemType.DOCUMENT to strings.get(R.string.item_type_document),
            ItemType.NOTE to strings.get(R.string.nav_notes),
            ItemType.BILLING_ADDRESS to strings.get(R.string.billing_address),
            ItemType.PAYMENT_ACCOUNT to strings.get(R.string.payment_account)
        )
        secureItems
            .groupBy { it.itemType }
            .toSortedMap(compareBy { TRASH_SECURE_ITEM_TYPES.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
            .forEach { (itemType, typedItems) ->
                val displayName = secureTypeLabels[itemType] ?: return@forEach
                val items = typedItems.map { item ->
                    val daysRemaining = if (settings.autoDeleteDays > 0 && item.deletedAt != null) {
                        val daysSinceDelete = TimeUnit.MILLISECONDS.toDays(now.time - item.deletedAt.time).toInt()
                        maxOf(0, settings.autoDeleteDays - daysSinceDelete)
                    } else -1
                    TrashItem(
                        id = item.id,
                        title = item.title,
                        itemType = itemType,
                        deletedAt = item.deletedAt ?: now,
                        daysRemaining = daysRemaining,
                        originalData = item
                    )
                }
                categories.add(
                    TrashCategory(
                        type = itemType,
                        displayName = displayName,
                        count = items.size,
                        items = items
                    )
                )
            }
        
        categories
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    // 回收站总条目数
    val totalTrashCount: StateFlow<Int> = trashCategories.map { categories ->
        categories.sumOf { it.count }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )
    
    /**
     * 恢复已删除的条目
     */
    fun restoreItem(item: TrashItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val local = when (val data = item.originalData) {
                    is PasswordEntry -> data.isLocalOnlyEntry()
                    is SecureItem -> data.keepassDatabaseId == null && data.mdbxDatabaseId == null && data.bitwardenVaultId == null
                    else -> false
                }
                if (local) {
                    val generation = takagi.ru.monica.security.SessionManager.lockGeneration.value
                    check(takagi.ru.monica.security.SessionManager.isUnlocked.value) { "Vault is locked" }
                    database.timelineJournal.operation {
                        check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                            takagi.ru.monica.security.SessionManager.lockGeneration.value == generation) { "Vault is locked" }
                        val unchanged = when (val data = item.originalData) {
                            is PasswordEntry -> data.isDeleted && database.passwordEntryDao().getPasswordEntryById(data.id) == data
                            is SecureItem -> data.isDeleted && database.secureItemDao().getItemById(data.id) == data
                            else -> false
                        }
                        check(unchanged) { "The recycle bin item changed; reopen its recovery preview" }
                        applyLocalRestore(item.originalData)
                        check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                            takagi.ru.monica.security.SessionManager.lockGeneration.value == generation) { "Vault is locked" }
                    }
                    onResult(true)
                    return@launch
                }
                val restoreOutcome = queueRemoteRestoreIfNeeded(item.originalData).getOrElse {
                    android.util.Log.e("TrashViewModel", "Queue remote restore failed for item id=${item.id}", it)
                    onResult(false)
                    return@launch
                }
                val restoreTarget = if (needsKeepassRestore(item.originalData)) {
                    restoreKeepassIfNeeded(item.originalData).getOrElse {
                        android.util.Log.e("TrashViewModel", "KeePass restore failed for item id=${item.id}", it)
                        onResult(false)
                        return@launch
                    }
                } else {
                    null
                }
                applyLocalRestore(
                    item.originalData,
                    restoreTarget = restoreTarget,
                    restoreOutcome = restoreOutcome
                )
                logTrashRestore(item)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to restore item", e)
                onResult(false)
                return@launch
            }
        }
    }
    
    /**
     * 永久删除条目
     */
    fun permanentlyDeleteItem(item: TrashItem, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = permanentlyDeleteWithSources(item.originalData)
                if (result.isSuccess) {
                    logTrashPermanentDelete(item)
                }
                onResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to permanently delete item", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun permanentlyDeleteItems(items: List<TrashItem>, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val (deletedCount, failure) = permanentlyDeleteTrashItems(items)
                if (deletedCount > 0) {
                    logTrashSummaryDelete(
                        title = getApplication<Application>().getString(R.string.timeline_permanent_delete_title),
                        detail = getApplication<Application>().getString(R.string.timeline_deleted_items_count, deletedCount)
                    )
                }
                onResult(if (failure == null) Result.success(Unit) else Result.failure(failure))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to permanently delete items", e)
                onResult(Result.failure(e))
            }
        }
    }
    
    /**
     * 恢复某个类别的所有条目
     */
    fun restoreCategory(category: TrashCategory, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val queuedPasswords = mutableListOf<PasswordEntry>()
            val queuedSecureItems = mutableListOf<SecureItem>()
            val restoreOutcomes = mutableMapOf<Long, BitwardenRestoreQueueOutcome>()
            val failedItemIds = mutableSetOf<Long>()
            var keepassPasswords: List<PasswordEntry> = emptyList()
            var keepassSecureItems: List<SecureItem> = emptyList()

            try {
                category.items.forEach { item ->
                    val restoreOutcome = queueRemoteRestoreIfNeeded(item.originalData).getOrNull()
                    if (restoreOutcome == null) {
                        failedItemIds += item.id
                        return@forEach
                    }
                    restoreOutcomes[item.id] = restoreOutcome
                    when (val data = item.originalData) {
                        is PasswordEntry -> queuedPasswords += data
                        is SecureItem -> queuedSecureItems += data
                    }
                }

                queuedPasswords
                    .filterNot { it.id in failedItemIds }
                    .forEach { entry ->
                        applyLocalRestore(
                            entry,
                            restoreOutcome = restoreOutcomes[entry.id]
                                ?: BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION
                        )
                    }
                queuedSecureItems
                    .filterNot { it.id in failedItemIds }
                    .forEach { item ->
                        applyLocalRestore(
                            item,
                            restoreOutcome = restoreOutcomes[item.id]
                                ?: BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION
                        )
                    }

                onResult(failedItemIds.isEmpty())

                keepassPasswords = queuedPasswords.filterNot { it.id in failedItemIds || it.keepassDatabaseId == null }
                keepassSecureItems = queuedSecureItems.filterNot { it.id in failedItemIds || it.keepassDatabaseId == null }
                if (keepassPasswords.isEmpty() && keepassSecureItems.isEmpty()) {
                    return@launch
                }
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to restore category", e)
                onResult(false)
                return@launch
            }

            viewModelScope.launch keepassBatchRestoreSync@{
                val keepassBatchResult = restoreKeepassBatchIfNeeded(
                    passwords = keepassPasswords,
                    secureItems = keepassSecureItems
                )
                keepassBatchResult.failedIds.forEach { failedId ->
                    queuedPasswords.firstOrNull { it.id == failedId }?.let { failedEntry ->
                        android.util.Log.e("TrashViewModel", "KeePass restore failed for password id=$failedId, rolling back local restore")
                        rollbackLocalRestore(failedEntry)
                    }
                    queuedSecureItems.firstOrNull { it.id == failedId }?.let { failedItem ->
                        android.util.Log.e("TrashViewModel", "KeePass restore failed for secure item id=$failedId, rolling back local restore")
                        rollbackLocalRestore(failedItem)
                    }
                }

                keepassPasswords.forEach { entry ->
                    if (entry.id in keepassBatchResult.failedIds) return@forEach
                    val restoreTarget = keepassBatchResult.passwordTargets[entry.id]
                    applyLocalRestore(
                        entry,
                        restoreTarget = restoreTarget,
                        restoreOutcome = restoreOutcomes[entry.id]
                            ?: BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION
                    )
                    android.util.Log.i("TrashViewModel", "KeePass restore synced: id=${entry.id}, type=${ItemType.PASSWORD}")
                }

                keepassSecureItems.forEach { item ->
                    if (item.id in keepassBatchResult.failedIds) return@forEach
                    val restoreTarget = keepassBatchResult.secureItemTargets[item.id]
                    applyLocalRestore(
                        item,
                        restoreTarget = restoreTarget,
                        restoreOutcome = restoreOutcomes[item.id]
                            ?: BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION
                    )
                    android.util.Log.i("TrashViewModel", "KeePass restore synced: id=${item.id}, type=${item.itemType}")
                }
            }
        }
    }
    
    /**
     * 清空回收站
     */
    fun emptyTrash(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val deletedPasswords = database.passwordEntryDao().getDeletedEntriesSync()
                val deletedSecureItems = database.secureItemDao().getDeletedItemsSync()
                val items = deletedPasswords.map { entry ->
                    TrashItem(
                        id = entry.id,
                        title = entry.title,
                        itemType = ItemType.PASSWORD,
                        deletedAt = entry.deletedAt ?: Date(),
                        daysRemaining = -1,
                        originalData = entry
                    )
                } + deletedSecureItems.map { item ->
                    TrashItem(
                        id = item.id,
                        title = item.title,
                        itemType = item.itemType,
                        deletedAt = item.deletedAt ?: Date(),
                        daysRemaining = -1,
                        originalData = item
                    )
                }
                val (deletedCount, failure) = permanentlyDeleteTrashItems(items)

                if (deletedCount > 0) {
                    logTrashSummaryDelete(
                        title = getApplication<Application>().getString(R.string.timeline_empty_trash_title),
                        detail = getApplication<Application>().getString(R.string.timeline_deleted_items_count, deletedCount)
                    )
                }

                onResult(if (failure == null) Result.success(Unit) else Result.failure(failure))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to empty trash", e)
                onResult(Result.failure(e))
            }
        }
    }
    
    /**
     * 清理过期的回收站条目（根据设置的自动清空天数）
     */
    fun cleanupExpiredItems() {
        viewModelScope.launch {
            cleanupExpiredItemsNow(trashSettings.value)
        }
    }

    private suspend fun cleanupExpiredItemsNow(settings: TrashSettings) {
            if (!settings.shouldAutoCleanup()) return

            val cutoffDate = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.autoDeleteDays.toLong()))
            
            try {
                var deletedCount = 0

                val expiredPasswords = database.passwordEntryDao()
                    .getDeletedEntriesSync()
                    .filter { it.deletedAt != null && it.deletedAt < cutoffDate }

                expiredPasswords.forEach { entry ->
                    if (permanentlyDeleteWithSources(entry).isSuccess) {
                        deletedCount += 1
                    }
                }

                val expiredSecureItems = database.secureItemDao()
                    .getDeletedItemsSync()
                    .filter { it.deletedAt != null && it.deletedAt < cutoffDate }

                expiredSecureItems.forEach { item ->
                    if (permanentlyDeleteWithSources(item).isSuccess) {
                        deletedCount += 1
                    }
                }

                if (deletedCount > 0) {
                    logTrashSummaryDelete(
                        title = getApplication<Application>().getString(R.string.timeline_trash_title),
                        detail = getApplication<Application>().getString(R.string.timeline_auto_clear_in_days, settings.autoDeleteDays)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrashViewModel", "Failed to cleanup expired items", e)
            }
    }

    private fun buildRestoredPasswordEntry(
        entry: PasswordEntry,
        restoreTarget: KeePassRestoreTarget?,
        restoreOutcome: BitwardenRestoreQueueOutcome
    ): PasswordEntry {
        return BitwardenTrashRestoreStateHelper.applyToPasswordEntry(
            candidate = entry.copy(
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                keepassGroupPath = restoreTarget?.groupPath,
                keepassGroupUuid = restoreTarget?.groupUuid
            ),
            restoreOutcome = restoreOutcome
        )
    }

    private fun buildRestoredSecureItem(
        item: SecureItem,
        restoreTarget: KeePassRestoreTarget?,
        restoreOutcome: BitwardenRestoreQueueOutcome
    ): SecureItem {
        return BitwardenTrashRestoreStateHelper.applyToSecureItem(
            candidate = item.copy(
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                keepassGroupPath = restoreTarget?.groupPath,
                keepassGroupUuid = restoreTarget?.groupUuid
            ),
            restoreOutcome = restoreOutcome
        )
    }

    private suspend fun applyLocalRestore(
        data: Any,
        restoreTarget: KeePassRestoreTarget? = null,
        restoreOutcome: BitwardenRestoreQueueOutcome = BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION
    ) {
        when (data) {
            is PasswordEntry -> {
                val resolvedTarget = restoreTarget ?: KeePassRestoreTarget(data.keepassGroupPath, data.keepassGroupUuid)
                val restoredEntry = buildRestoredPasswordEntry(
                    entry = data,
                    restoreTarget = resolvedTarget,
                    restoreOutcome = restoreOutcome
                )
                logMdbxTrashPasswordRestore(
                    op = "apply",
                    before = data,
                    after = restoredEntry
                )
                passwordRepository.updatePasswordEntry(restoredEntry)
            }
            is SecureItem -> {
                val resolvedTarget = restoreTarget ?: KeePassRestoreTarget(data.keepassGroupPath, data.keepassGroupUuid)
                val restoredItem = buildRestoredSecureItem(
                    item = data,
                    restoreTarget = resolvedTarget,
                    restoreOutcome = restoreOutcome
                )
                logMdbxTrashSecureItemRestore(
                    op = "apply",
                    before = data,
                    after = restoredItem
                )
                secureItemRepository.updateItem(restoredItem)
            }
        }
    }

    private suspend fun rollbackLocalRestore(data: Any) {
        when (data) {
            is PasswordEntry -> {
                val rollbackEntry = data.copy(updatedAt = Date())
                logMdbxTrashPasswordRestore(
                    op = "rollback",
                    before = data,
                    after = rollbackEntry
                )
                passwordRepository.updatePasswordEntry(rollbackEntry)
            }
            is SecureItem -> {
                val rollbackItem = data.copy(updatedAt = Date())
                logMdbxTrashSecureItemRestore(
                    op = "rollback",
                    before = data,
                    after = rollbackItem
                )
                secureItemRepository.updateItem(rollbackItem)
            }
        }
    }

    private fun logMdbxTrashPasswordRestore(
        op: String,
        before: PasswordEntry,
        after: PasswordEntry
    ) {
        val databaseId = after.mdbxDatabaseId ?: before.mdbxDatabaseId ?: return
        MdbxDiagLogger.append(
            "[MDBX][trash-restore] type=password op=$op databaseId=$databaseId roomId=${before.id} beforeEntryId=${before.replicaGroupId ?: "-"} afterEntryId=${after.replicaGroupId ?: "-"} beforeDeleted=${before.isDeleted} afterDeleted=${after.isDeleted} beforeUpdatedAt=${before.updatedAt.time} afterUpdatedAt=${after.updatedAt.time} beforeDeletedAt=${before.deletedAt?.time ?: "-"} afterDeletedAt=${after.deletedAt?.time ?: "-"}"
        )
    }

    private fun logMdbxTrashSecureItemRestore(
        op: String,
        before: SecureItem,
        after: SecureItem
    ) {
        val databaseId = after.mdbxDatabaseId ?: before.mdbxDatabaseId ?: return
        MdbxDiagLogger.append(
            "[MDBX][trash-restore] type=secure_item op=$op databaseId=$databaseId roomId=${before.id} itemType=${before.itemType} beforeEntryId=${before.replicaGroupId ?: "-"} afterEntryId=${after.replicaGroupId ?: "-"} beforeDeleted=${before.isDeleted} afterDeleted=${after.isDeleted} beforeUpdatedAt=${before.updatedAt.time} afterUpdatedAt=${after.updatedAt.time} beforeDeletedAt=${before.deletedAt?.time ?: "-"} afterDeletedAt=${after.deletedAt?.time ?: "-"}"
        )
    }

    private fun needsKeepassRestore(data: Any): Boolean = when (data) {
        is PasswordEntry -> data.keepassDatabaseId != null
        is SecureItem -> data.keepassDatabaseId != null
        else -> false
    }

    private suspend fun restoreKeepassIfNeeded(data: Any): Result<KeePassRestoreTarget?> {
        return when (data) {
            is PasswordEntry -> {
                val keepassId = data.keepassDatabaseId
                if (keepassId == null) {
                    val localTarget = KeePassRestoreTarget(data.keepassGroupPath, data.keepassGroupUuid)
                    return Result.success(localTarget)
                }
                val restoredTargets = keepassBridge.restoreLegacyPasswordEntriesFromRecycleBin(
                    databaseId = keepassId,
                    entries = listOf(data.copy(keepassDatabaseId = keepassId))
                ).getOrElse { return Result.failure(it) }
                val restoreTarget = restoredTargets[data.id]
                    ?: return Result.failure(IllegalStateException("KeePass recycle restore did not restore password id=${data.id}"))
                Result.success(restoreTarget)
            }
            is SecureItem -> {
                val keepassId = data.keepassDatabaseId
                if (keepassId == null) {
                    val localTarget = KeePassRestoreTarget(data.keepassGroupPath, data.keepassGroupUuid)
                    return Result.success(localTarget)
                }
                val restoredTargets = keepassBridge.restoreLegacySecureItemsFromRecycleBin(
                    databaseId = keepassId,
                    items = listOf(data.copy(keepassDatabaseId = keepassId))
                ).getOrElse { return Result.failure(it) }
                val restoreTarget = restoredTargets[data.id]
                    ?: return Result.failure(IllegalStateException("KeePass recycle restore did not restore secure item id=${data.id}"))
                Result.success(restoreTarget)
            }
            else -> Result.success(null)
        }
    }

    private data class KeepassBatchRestoreResult(
        val passwordTargets: Map<Long, KeePassRestoreTarget?>,
        val secureItemTargets: Map<Long, KeePassRestoreTarget?>,
        val failedIds: Set<Long>
    )

    private suspend fun restoreKeepassBatchIfNeeded(
        passwords: List<PasswordEntry>,
        secureItems: List<SecureItem>
    ): KeepassBatchRestoreResult {
        val restoredPasswordTargets = mutableMapOf<Long, KeePassRestoreTarget?>()
        val restoredSecureTargets = mutableMapOf<Long, KeePassRestoreTarget?>()
        val failedIds = mutableSetOf<Long>()

        // Local-only items are considered restored in place.
        passwords.filter { it.keepassDatabaseId == null }.forEach {
            restoredPasswordTargets[it.id] = KeePassRestoreTarget(it.keepassGroupPath, it.keepassGroupUuid)
        }
        secureItems.filter { it.keepassDatabaseId == null }.forEach {
            restoredSecureTargets[it.id] = KeePassRestoreTarget(it.keepassGroupPath, it.keepassGroupUuid)
        }

        val groupedPasswords = passwords
            .filter { it.keepassDatabaseId != null }
            .groupBy { it.keepassDatabaseId!! }
        groupedPasswords.forEach { (databaseId, entries) ->
            val restoreResult = keepassBridge.restoreLegacyPasswordEntriesFromRecycleBin(
                databaseId = databaseId,
                entries = entries.map { it.copy(keepassDatabaseId = databaseId) }
            )
            if (restoreResult.isFailure) {
                android.util.Log.e(
                    "TrashViewModel",
                    "KeePass batch restore failed for passwords db=$databaseId",
                    restoreResult.exceptionOrNull()
                )
                failedIds += entries.map { it.id }
                return@forEach
            }

            val restoredById = restoreResult.getOrNull().orEmpty()
            entries.forEach { entry ->
                val restoreTarget = restoredById[entry.id]
                if (restoreTarget == null) {
                    failedIds += entry.id
                } else {
                    restoredPasswordTargets[entry.id] = restoreTarget
                }
            }
        }

        val groupedSecureItems = secureItems
            .filter { it.keepassDatabaseId != null }
            .groupBy { it.keepassDatabaseId!! }
        groupedSecureItems.forEach { (databaseId, items) ->
            val restoreResult = keepassBridge.restoreLegacySecureItemsFromRecycleBin(
                databaseId = databaseId,
                items = items.map { it.copy(keepassDatabaseId = databaseId) }
            )
            if (restoreResult.isFailure) {
                android.util.Log.e(
                    "TrashViewModel",
                    "KeePass batch restore failed for secure items db=$databaseId",
                    restoreResult.exceptionOrNull()
                )
                failedIds += items.map { it.id }
                return@forEach
            }

            val restoredById = restoreResult.getOrNull().orEmpty()
            items.forEach { item ->
                val restoreTarget = restoredById[item.id]
                if (restoreTarget == null) {
                    failedIds += item.id
                } else {
                    restoredSecureTargets[item.id] = restoreTarget
                }
            }
        }

        return KeepassBatchRestoreResult(
            passwordTargets = restoredPasswordTargets,
            secureItemTargets = restoredSecureTargets,
            failedIds = failedIds
        )
    }

    private suspend fun deleteKeepassEntryIfNeeded(data: Any): Result<Unit> {
        return when (data) {
            is PasswordEntry -> {
                val keepassId = data.keepassDatabaseId ?: return Result.success(Unit)
                val result = keepassBridge.deleteLegacyPasswordEntries(
                    databaseId = keepassId,
                    entries = listOf(data.copy(keepassDatabaseId = keepassId))
                )
                if (result.isFailure) {
                    android.util.Log.e(
                        "TrashViewModel",
                        "KeePass permanent delete failed for password id=${data.id}, db=$keepassId",
                        result.exceptionOrNull()
                    )
                    return Result.failure(checkNotNull(result.exceptionOrNull()))
                }
                val deletedCount = result.getOrNull() ?: 0
                if (deletedCount <= 0) {
                    android.util.Log.w("TrashViewModel", "KeePass password already absent during permanent delete: id=${data.id}, db=$keepassId")
                }
                Result.success(Unit)
            }
            is SecureItem -> {
                val keepassId = data.keepassDatabaseId ?: return Result.success(Unit)
                val result = keepassBridge.deleteLegacySecureItems(
                    databaseId = keepassId,
                    items = listOf(data.copy(keepassDatabaseId = keepassId))
                )
                if (result.isFailure) {
                    android.util.Log.e(
                        "TrashViewModel",
                        "KeePass permanent delete failed for secure item id=${data.id}, db=$keepassId",
                        result.exceptionOrNull()
                    )
                    return Result.failure(checkNotNull(result.exceptionOrNull()))
                }
                val deletedCount = result.getOrNull() ?: 0
                if (deletedCount <= 0) {
                    android.util.Log.w("TrashViewModel", "KeePass secure item already absent during permanent delete: id=${data.id}, db=$keepassId")
                }
                Result.success(Unit)
            }
            else -> Result.success(Unit)
        }
    }

    private suspend fun permanentlyDeleteTrashItems(items: List<TrashItem>): Pair<Int, Throwable?> {
        var firstFailure: Throwable? = null
        var deletedCount = 0
        items.forEach { item ->
            val result = permanentlyDeleteWithSources(item.originalData)
            if (result.isSuccess) {
                deletedCount += 1
            } else if (firstFailure == null) {
                firstFailure = result.exceptionOrNull()
            }
        }
        return deletedCount to firstFailure
    }

    private suspend fun permanentlyDeleteWithSources(data: Any): Result<Unit> {
        return try {
            deleteRemoteCipherIfNeeded(data).getOrThrow()
            deleteKeepassEntryIfNeeded(data).getOrThrow()
            // Keep a failed source deletion visible and retryable in the local recycle bin.
            when (data) {
                is PasswordEntry -> database.passwordEntryDao().delete(data)
                is SecureItem -> database.secureItemDao().delete(data)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("TrashViewModel", "Failed to permanently delete item from its sources", e)
            Result.failure(e)
        }
    }

    private suspend fun deleteRemoteCipherIfNeeded(data: Any): Result<Unit> {
        return when (data) {
            is PasswordEntry -> {
                val vaultId = data.bitwardenVaultId
                val cipherId = data.bitwardenCipherId
                if (vaultId != null && !cipherId.isNullOrBlank()) {
                    bitwardenRepository.permanentDeleteCipher(vaultId, cipherId)
                } else {
                    Result.success(Unit)
                }
            }
            is SecureItem -> {
                val vaultId = data.bitwardenVaultId
                val cipherId = data.bitwardenCipherId
                if (vaultId != null && !cipherId.isNullOrBlank()) {
                    bitwardenRepository.permanentDeleteCipher(vaultId, cipherId)
                } else {
                    Result.success(Unit)
                }
            }
            else -> Result.success(Unit)
        }
    }

    private suspend fun queueRemoteRestoreIfNeeded(data: Any): Result<BitwardenRestoreQueueOutcome> {
        return when (data) {
            is PasswordEntry -> {
                val vaultId = data.bitwardenVaultId
                val cipherId = data.bitwardenCipherId
                if (vaultId != null && !cipherId.isNullOrBlank()) {
                    bitwardenRepository.queueCipherRestore(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = data.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_PASSWORD
                    )
                } else {
                    Result.success(BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION)
                }
            }
            is SecureItem -> {
                val vaultId = data.bitwardenVaultId
                val cipherId = data.bitwardenCipherId
                if (vaultId != null && !cipherId.isNullOrBlank()) {
                    bitwardenRepository.queueCipherRestore(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = data.id,
                        itemType = data.itemType.toPendingItemType()
                    )
                } else {
                    Result.success(BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION)
                }
            }
            else -> Result.success(BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION)
        }
    }

    private fun ItemType.toPendingItemType(): String = when (this) {
        ItemType.PASSWORD -> BitwardenPendingOperation.ITEM_TYPE_PASSWORD
        ItemType.TOTP -> BitwardenPendingOperation.ITEM_TYPE_TOTP
        ItemType.BANK_CARD -> BitwardenPendingOperation.ITEM_TYPE_CARD
        ItemType.DOCUMENT -> BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
        ItemType.BILLING_ADDRESS -> BitwardenPendingOperation.ITEM_TYPE_BILLING_ADDRESS
        ItemType.PAYMENT_ACCOUNT -> BitwardenPendingOperation.ITEM_TYPE_PAYMENT_ACCOUNT
        ItemType.NOTE -> BitwardenPendingOperation.ITEM_TYPE_NOTE
    }

    private fun ItemType.toOperationLogItemType(): OperationLogItemType = when (this) {
        ItemType.PASSWORD -> OperationLogItemType.PASSWORD
        ItemType.TOTP -> OperationLogItemType.TOTP
        ItemType.BANK_CARD -> OperationLogItemType.BANK_CARD
        ItemType.DOCUMENT -> OperationLogItemType.DOCUMENT
        ItemType.BILLING_ADDRESS -> OperationLogItemType.BILLING_ADDRESS
        ItemType.PAYMENT_ACCOUNT -> OperationLogItemType.PAYMENT_ACCOUNT
        ItemType.NOTE -> OperationLogItemType.NOTE
    }

    private fun logTrashRestore(item: TrashItem) {
        OperationLogger.logUpdate(
            itemType = item.itemType.toOperationLogItemType(),
            itemId = item.id,
            itemTitle = item.title,
            changes = listOf(
                FieldChange(
                    fieldName = getApplication<Application>().getString(R.string.timeline_trash_title),
                    oldValue = getApplication<Application>().getString(R.string.timeline_op_delete),
                    newValue = getApplication<Application>().getString(R.string.timeline_reverted)
                )
            )
        )
    }

    private fun logTrashPermanentDelete(item: TrashItem) {
        OperationLogger.logDelete(
            itemType = item.itemType.toOperationLogItemType(),
            itemId = item.id,
            itemTitle = item.title,
            detail = getApplication<Application>().getString(R.string.timeline_permanent_delete_title)
        )
    }

    private fun logTrashSummaryDelete(title: String, detail: String) {
        OperationLogger.logDelete(
            itemType = OperationLogItemType.CATEGORY,
            itemId = System.currentTimeMillis(),
            itemTitle = title,
            detail = detail
        )
    }
    
    /**
     * 更新回收站设置
     */
    fun updateTrashSettings(enabled: Boolean, autoDeleteDays: Int) {
        viewModelScope.launch {
            settingsManager.updateTrashEnabled(enabled)
            settingsManager.updateTrashAutoDeleteDays(autoDeleteDays)
        }
    }
}

/**
 * 回收站设置数据类
 */
data class TrashSettings(
    val enabled: Boolean = true,
    val autoDeleteDays: Int = 30  // 0 = 不自动清空, -1 = 禁用回收站
)

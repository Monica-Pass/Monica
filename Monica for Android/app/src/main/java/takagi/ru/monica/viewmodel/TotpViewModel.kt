package takagi.ru.monica.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import takagi.ru.monica.keepass.KeePassCrossDatabaseTransfer
import takagi.ru.monica.keepass.KeePassSecureItemCreateExecutor
import takagi.ru.monica.keepass.KeePassSecureItemDeleteExecutor
import takagi.ru.monica.keepass.KeePassSecureItemUpdateExecutor
import takagi.ru.monica.keepass.KeePassTotpProjectionMatcher
import takagi.ru.monica.bitwarden.BitwardenHistoricalRepairStateHelper
import takagi.ru.monica.bitwarden.SecureItemBitwardenTransitionResolver
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.asMonicaLocalCopy
import takagi.ru.monica.data.hasBitwardenBinding
import takagi.ru.monica.data.hasOwnershipConflict
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.storageScopeKey
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncItemKind
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.readKeePassKeyFileBytes
import takagi.ru.monica.utils.toKeePassOperationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpMigrationBatchPlan
import takagi.ru.monica.util.TotpParseResult
import takagi.ru.monica.util.planTotpMigrationBatch
import kotlin.coroutines.resume

/**
 * 验证器分类过滤器
 */
sealed class TotpCategoryFilter {
    object All : TotpCategoryFilter()
    object Local : TotpCategoryFilter()
    object Starred : TotpCategoryFilter()
    object Uncategorized : TotpCategoryFilter()
    object LocalStarred : TotpCategoryFilter()
    object LocalUncategorized : TotpCategoryFilter()
    data class Custom(val categoryId: Long) : TotpCategoryFilter()
    data class KeePassDatabase(val databaseId: Long) : TotpCategoryFilter()
    data class KeePassGroupFilter(
        val databaseId: Long,
        val groupPath: String,
        val groupUuid: String? = null
    ) : TotpCategoryFilter()
    data class KeePassDatabaseStarred(val databaseId: Long) : TotpCategoryFilter()
    data class KeePassDatabaseUncategorized(val databaseId: Long) : TotpCategoryFilter()
    data class BitwardenVault(val vaultId: Long) : TotpCategoryFilter()
    data class BitwardenFolderFilter(val folderId: String, val vaultId: Long) : TotpCategoryFilter()
    data class BitwardenVaultStarred(val vaultId: Long) : TotpCategoryFilter()
    data class BitwardenVaultUncategorized(val vaultId: Long) : TotpCategoryFilter()
    data class MdbxDatabase(val databaseId: Long) : TotpCategoryFilter()
}

data class TotpMigrationSaveResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val failedCount: Int,
    val validationRejected: Boolean
)

data class ParsedTotpItem(
    val item: SecureItem,
    val totpData: TotpData
)

/**
 * TOTP验证器ViewModel
 */
class TotpViewModel(
    private val repository: SecureItemRepository,
    private val passwordRepository: PasswordRepository,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    private val securityManager: SecurityManager? = null
) : ViewModel() {
    private val applicationContext = context?.applicationContext

    private data class KeePassMutationIdentity(
        val groupPath: String?,
        val entryUuid: String?,
        val groupUuid: String?
    )

    data class BitwardenTotpRepairResult(
        val normalizedTotpItems: Int,
        val queuedTotpItemsForSync: Int,
        val normalizedPasswords: Int,
        val queuedPasswordsForSync: Int,
        val skippedItems: Int
    ) {
        val normalizedCount: Int
            get() = normalizedTotpItems + normalizedPasswords

        val queuedForSyncCount: Int
            get() = queuedTotpItemsForSync + queuedPasswordsForSync
    }

    companion object {
        private const val FILTER_ALL = "all"
        private const val FILTER_LOCAL = "local"
        private const val FILTER_STARRED = "starred"
        private const val FILTER_UNCATEGORIZED = "uncategorized"
        private const val FILTER_LOCAL_STARRED = "local_starred"
        private const val FILTER_LOCAL_UNCATEGORIZED = "local_uncategorized"
        private const val FILTER_CUSTOM = "custom"
        private const val FILTER_KEEPASS_DATABASE = "keepass_database"
        private const val FILTER_KEEPASS_GROUP = "keepass_group"
        private const val FILTER_KEEPASS_DATABASE_STARRED = "keepass_database_starred"
        private const val FILTER_KEEPASS_DATABASE_UNCATEGORIZED = "keepass_database_uncategorized"
        private const val FILTER_BITWARDEN_VAULT = "bitwarden_vault"
        private const val FILTER_BITWARDEN_FOLDER = "bitwarden_folder"
        private const val FILTER_BITWARDEN_VAULT_STARRED = "bitwarden_vault_starred"
        private const val FILTER_BITWARDEN_VAULT_UNCATEGORIZED = "bitwarden_vault_uncategorized"
        private const val FILTER_MDBX_DATABASE = "mdbx_database"
    }

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
    private val keepassSecureItemCreateExecutor = KeePassSecureItemCreateExecutor(keepassBridge)
    private val keepassSecureItemDeleteExecutor = KeePassSecureItemDeleteExecutor(keepassBridge)
    private val keepassSecureItemUpdateExecutor = KeePassSecureItemUpdateExecutor(keepassBridge)
    private val bitwardenRepository = context?.let { BitwardenRepository.getInstance(it.applicationContext) }
    private val settingsManager = context?.let { SettingsManager(it.applicationContext) }
    private val parsedTotpDataCache = ConcurrentHashMap<String, TotpData>()

    private fun requestBitwardenMutationSync(vaultId: Long?) {
        vaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
    }

    private fun decryptStoredSensitiveValue(value: String): String {
        return securityManager?.decryptDataIfMonicaCiphertext(value) ?: value
    }

    private fun looksLikeStoredSensitiveCiphertext(value: String): Boolean {
        return securityManager?.looksLikeMonicaCiphertext(value) == true
    }

    private fun encodeStoredSensitiveValueForRewrite(originalValue: String, plainValue: String): String {
        return if (looksLikeStoredSensitiveCiphertext(originalValue)) {
            securityManager?.encryptDataLegacyCompat(plainValue) ?: plainValue
        } else {
            plainValue
        }
    }

    private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String {
        if (plainValue.isBlank()) return plainValue
        return securityManager?.encryptDataLegacyCompat(plainValue) ?: plainValue
    }

    private suspend fun updatePasswordAuthenticatorKeyForStorage(passwordId: Long, plainAuthenticatorKey: String) {
        val storedAuthenticatorKey = encodeStoredSensitiveValueForNewWrite(plainAuthenticatorKey)
        passwordRepository.updateAuthenticatorKey(passwordId, storedAuthenticatorKey)
    }

    private fun parseStoredTotpData(
        item: SecureItem,
        fallbackIssuer: String = item.title,
        fallbackAccountName: String = ""
    ): TotpData? {
        val cacheKey = buildString {
            append("item|")
            append(item.id)
            append('|')
            append(item.itemData)
            append('|')
            append(fallbackIssuer)
            append('|')
            append(fallbackAccountName)
        }
        return cachedParsedTotpData(cacheKey) {
            TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = fallbackIssuer,
                fallbackAccountName = fallbackAccountName,
                decryptIfNeeded = ::decryptStoredSensitiveValue
            )
        }
    }

    fun parseTotpDataForDisplay(item: SecureItem): TotpData? {
        return parseStoredTotpData(item)
    }

    private fun parsePasswordAuthenticatorKey(password: PasswordEntry): TotpData? {
        val cacheKey = buildString {
            append("password|")
            append(password.id)
            append('|')
            append(password.authenticatorKey)
            append('|')
            append(password.title)
            append('|')
            append(password.username)
        }
        return cachedParsedTotpData(cacheKey) {
            TotpDataResolver.fromAuthenticatorKey(
                rawKey = decryptStoredSensitiveValue(password.authenticatorKey),
                fallbackIssuer = password.title,
                fallbackAccountName = password.username
            )
        }
    }

    private fun cachedParsedTotpData(cacheKey: String, parser: () -> TotpData?): TotpData? {
        parsedTotpDataCache[cacheKey]?.let { return it }
        val parsed = parser() ?: return null
        if (parsedTotpDataCache.size > 1024) {
            parsedTotpDataCache.clear()
        }
        parsedTotpDataCache[cacheKey] = parsed
        return parsed
    }
    
    // 搜索查询
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // 分类过滤器
    private val _categoryFilter = MutableStateFlow<TotpCategoryFilter>(TotpCategoryFilter.All)
    val categoryFilter: StateFlow<TotpCategoryFilter> = _categoryFilter.asStateFlow()

    init {
        restoreLastCategoryFilter()
        // Legacy binding repair scans the shared secure-item table. Keep it
        // off the main dispatcher so it cannot delay the first authenticator frame.
        viewModelScope.launch(Dispatchers.Default) {
            repairLegacyDetachedKeePassItems()
        }
    }
    
    // 分类列表（使用 PasswordRepository 获取）
    val categories: StateFlow<List<Category>> = passwordRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 当前 Bitwarden Vault 筛选下的文件夹集合。
     * 兼容历史数据: 某些旧数据可能只有 folderId，没有写入 vaultId。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedBitwardenVaultFolderIds: StateFlow<Set<String>> = _categoryFilter
        .flatMapLatest { filter ->
            val folderFlow = when (filter) {
                is TotpCategoryFilter.BitwardenVault -> {
                    passwordRepository.getBitwardenFoldersByVaultId(filter.vaultId)
                }
                is TotpCategoryFilter.BitwardenVaultStarred -> {
                    passwordRepository.getBitwardenFoldersByVaultId(filter.vaultId)
                }
                else -> flowOf(emptyList())
            }
            folderFlow.map { folders -> folders.mapTo(linkedSetOf()) { it.bitwardenFolderId } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private fun mergeStoredAndVirtualTotps(
        storedTotps: List<SecureItem>,
        allPasswords: List<PasswordEntry>
    ): List<SecureItem> {
        val displayStoredTotps = collapseDuplicateBoundStoredTotps(storedTotps)
        val existingKeys = storedTotps.mapNotNull { item ->
            parseStoredTotpData(item)
                ?.let(::buildTotpIdentityKey)
        }.toSet()

        val seenVirtualKeys = mutableSetOf<String>()
        val virtualTotps = allPasswords.mapNotNull { password ->
            val resolvedTotpData = resolvePasswordAuthenticatorTotp(password) ?: return@mapNotNull null
            val identityKey = buildTotpIdentityKey(resolvedTotpData)
            if (identityKey in existingKeys || !seenVirtualKeys.add(identityKey)) {
                return@mapNotNull null
            }

            SecureItem(
                id = -password.id,
                itemType = ItemType.TOTP,
                title = password.title,
                notes = "来自密码: ${password.title}",
                itemData = Json.encodeToString(resolvedTotpData),
                isFavorite = false,
                createdAt = password.createdAt,
                updatedAt = password.updatedAt,
                imagePaths = "",
                categoryId = password.categoryId,
                keepassDatabaseId = password.keepassDatabaseId,
                keepassGroupPath = password.keepassGroupPath,
                bitwardenVaultId = password.bitwardenVaultId,
                bitwardenFolderId = password.bitwardenFolderId,
                mdbxDatabaseId = password.mdbxDatabaseId
            )
        }

        return displayStoredTotps + virtualTotps
    }

    private fun collapseDuplicateBoundStoredTotps(storedTotps: List<SecureItem>): List<SecureItem> {
        val seenBoundKeys = mutableSetOf<String>()
        return storedTotps.filter { item ->
            val data = parseStoredTotpData(item)
                ?: return@filter true
            val boundPasswordId = data.boundPasswordId ?: return@filter true
            val key = "$boundPasswordId|${buildTotpIdentityKey(data)}"
            seenBoundKeys.add(key)
        }
    }

    // Both the authenticator UI and the keyboard/detail panes consume the
    // merged TOTP list. Keep one shared upstream subscription so a page entry
    // does not start multiple Room queries and password-key parsing passes.
    private val allTotpItemsSharingStarted = SharingStarted.WhileSubscribed(5000)
    private val allTotpItemsSource: SharedFlow<List<SecureItem>> = combine(
        repository.getItemsByType(ItemType.TOTP),
        passwordRepository.getAllPasswordEntries()
    ) { storedTotps, allPasswords ->
        mergeStoredAndVirtualTotps(
            storedTotps = storedTotps,
            allPasswords = allPasswords
        )
    }.flowOn(Dispatchers.Default)
        .shareIn(
            scope = viewModelScope,
            started = allTotpItemsSharingStarted,
            replay = 1,
        )

    val allTotpItems: StateFlow<List<SecureItem>> = allTotpItemsSource.stateIn(
        scope = viewModelScope,
        started = allTotpItemsSharingStarted,
        initialValue = emptyList()
    )
    
    // TOTP项目列表 - 合并实际存储的TOTP和从密码authenticatorKey生成的虚拟TOTP
    val totpItems: StateFlow<List<SecureItem>> = combine(
        _searchQuery,
        _categoryFilter,
        allTotpItems,
        selectedBitwardenVaultFolderIds
    ) { query, filter, allTotps, selectedVaultFolderIds ->
        // 首先应用分类过滤
        val categoryFiltered = when (filter) {
            is TotpCategoryFilter.All -> allTotps
            is TotpCategoryFilter.Local -> allTotps.filter { it.isLocalOnlyItem() }
            is TotpCategoryFilter.Starred -> allTotps.filter { it.isFavorite }
            is TotpCategoryFilter.Uncategorized -> allTotps.filter { 
                it.categoryId == null && (parseStoredTotpData(it)?.categoryId == null)
            }
            is TotpCategoryFilter.LocalStarred -> allTotps.filter {
                it.isLocalOnlyItem() && it.isFavorite
            }
            is TotpCategoryFilter.LocalUncategorized -> allTotps.filter {
                it.isLocalOnlyItem() && (
                    it.categoryId == null && (parseStoredTotpData(it)?.categoryId == null)
                )
            }
            is TotpCategoryFilter.Custom -> allTotps.filter { item ->
                item.isLocalOnlyItem() && (
                    item.categoryId == filter.categoryId ||
                        parseStoredTotpData(item)?.categoryId == filter.categoryId
                )
            }
            is TotpCategoryFilter.KeePassDatabase -> allTotps.filter {
                (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId
            }
            is TotpCategoryFilter.KeePassGroupFilter -> allTotps.filter {
                takagi.ru.monica.ui.KeePassGroupFilterIdentity(
                    filter.databaseId,
                    filter.groupPath,
                    filter.groupUuid
                ).matches(
                    itemDatabaseId = (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId,
                    itemGroupPath = it.keepassGroupPath,
                    itemGroupUuid = it.keepassGroupUuid
                )
            }
            is TotpCategoryFilter.KeePassDatabaseStarred -> allTotps.filter {
                (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                    it.isFavorite
            }
            is TotpCategoryFilter.KeePassDatabaseUncategorized -> allTotps.filter {
                (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                    it.keepassGroupPath.isNullOrBlank()
            }
            is TotpCategoryFilter.BitwardenVault -> allTotps.filter {
                ((it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId) ||
                    (
                        it.bitwardenVaultId == null &&
                            !it.bitwardenFolderId.isNullOrBlank() &&
                            it.bitwardenFolderId in selectedVaultFolderIds
                        )
            }
            is TotpCategoryFilter.BitwardenFolderFilter -> allTotps.filter {
                it.hasBitwardenBinding() && it.bitwardenFolderId == filter.folderId
            }
            is TotpCategoryFilter.BitwardenVaultStarred -> allTotps.filter {
                (
                    (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId ||
                        (
                            it.bitwardenVaultId == null &&
                                !it.bitwardenFolderId.isNullOrBlank() &&
                                it.bitwardenFolderId in selectedVaultFolderIds
                            )
                    ) && it.isFavorite
            }
            is TotpCategoryFilter.BitwardenVaultUncategorized -> allTotps.filter {
                (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId &&
                    it.bitwardenFolderId == null
            }
            is TotpCategoryFilter.MdbxDatabase -> allTotps.filter {
                (it.resolveOwnership() as? SecureItemOwnership.Mdbx)?.databaseId == filter.databaseId
            }
        }
        
        // 然后应用搜索过滤
        if (query.isBlank()) {
            categoryFiltered
        } else {
            categoryFiltered.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                item.notes.contains(query, ignoreCase = true) ||
                parseStoredTotpData(item)?.let { data ->
                    data.issuer.contains(query, ignoreCase = true) ||
                        data.accountName.contains(query, ignoreCase = true)
                } ?: false
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val parsedTotpItems: StateFlow<List<ParsedTotpItem>> = totpItems
        .map { items ->
            withContext(Dispatchers.Default) {
                items.map { item ->
                    ParsedTotpItem(
                        item = item,
                        totpData = parseStoredTotpData(item) ?: TotpData(secret = "")
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * 更新搜索查询
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun revealSavedTotpTargets(targets: List<StorageTarget>) {
        updateSearchQuery("")
        setCategoryFilter(targets.firstOrNull()?.toTotpCategoryFilter() ?: TotpCategoryFilter.All)
    }
    
    /**
     * 设置分类过滤器
     */
    fun setCategoryFilter(filter: TotpCategoryFilter) {
        _categoryFilter.value = filter
        persistCategoryFilter(filter)
        when (filter) {
            is TotpCategoryFilter.KeePassDatabase -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassTotp(filter.databaseId)
            }
            is TotpCategoryFilter.KeePassGroupFilter -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassTotp(filter.databaseId)
            }
            is TotpCategoryFilter.KeePassDatabaseStarred -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassTotp(filter.databaseId)
            }
            is TotpCategoryFilter.KeePassDatabaseUncategorized -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassTotp(filter.databaseId)
            }
            is TotpCategoryFilter.MdbxDatabase -> Unit
            else -> KeePassKdbxService.trimInactiveCaches()
        }
    }

    private fun StorageTarget.toTotpCategoryFilter(): TotpCategoryFilter {
        return when (this) {
            is StorageTarget.MonicaLocal -> categoryId
                ?.let(TotpCategoryFilter::Custom)
                ?: TotpCategoryFilter.LocalUncategorized
            is StorageTarget.KeePass -> groupPath
                ?.takeIf { it.isNotBlank() }
                ?.let { TotpCategoryFilter.KeePassGroupFilter(databaseId, it) }
                ?: TotpCategoryFilter.KeePassDatabaseUncategorized(databaseId)
            is StorageTarget.Bitwarden -> folderId
                ?.takeIf { it.isNotBlank() }
                ?.let { TotpCategoryFilter.BitwardenFolderFilter(it, vaultId) }
                ?: TotpCategoryFilter.BitwardenVaultUncategorized(vaultId)
            is StorageTarget.Mdbx -> TotpCategoryFilter.MdbxDatabase(databaseId)
        }
    }

    private fun resolvePasswordAuthenticatorTotp(password: PasswordEntry): TotpData? {
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptStoredSensitiveValue(password.authenticatorKey),
            fallbackIssuer = password.website.takeIf { it.isNotBlank() } ?: password.title,
            fallbackAccountName = password.username.takeIf { it.isNotBlank() } ?: password.title
        )?.copy(
            boundPasswordId = password.id,
            categoryId = password.categoryId
        )
    }

    private fun buildTotpIdentityKey(data: TotpData): String {
        val normalized = TotpDataResolver.normalizeTotpData(data)
        return listOf(
            normalized.otpType.name,
            normalized.secret,
            normalized.algorithm.uppercase(Locale.ROOT),
            normalized.digits.toString(),
            normalized.period.toString(),
            normalized.counter.toString()
        ).joinToString("|")
    }

    private fun buildTotpIdentityKeyFromRawKey(rawKey: String): String? {
        return TotpDataResolver.fromAuthenticatorKey(
            decryptStoredSensitiveValue(rawKey)
        )?.let(::buildTotpIdentityKey)
    }

    suspend fun repairHistoricalBitwardenTotp(vaultId: Long): BitwardenTotpRepairResult {
        bitwardenRepository?.let { repo ->
            val result = repo.repairHistoricalBitwardenTotp(vaultId).getOrThrow()
            return BitwardenTotpRepairResult(
                normalizedTotpItems = result.normalizedTotpItems,
                queuedTotpItemsForSync = result.queuedTotpItemsForSync,
                normalizedPasswords = result.normalizedPasswords,
                queuedPasswordsForSync = result.queuedPasswordsForSync,
                skippedItems = result.skippedItems
            )
        }

        return repairHistoricalBitwardenTotpLocally(vaultId)
    }

    private suspend fun repairHistoricalBitwardenTotpLocally(vaultId: Long): BitwardenTotpRepairResult {
        val now = Date()
        var normalizedTotpItems = 0
        var queuedTotpItemsForSync = 0
        var normalizedPasswords = 0
        var queuedPasswordsForSync = 0
        var skippedItems = 0

        val secureItems = repository.getItemsByType(ItemType.TOTP).first()
        secureItems
            .asSequence()
            .filter { it.bitwardenVaultId == vaultId && !it.isDeleted }
            .forEach { item ->
                val normalizedData = parseStoredTotpData(item)
                if (normalizedData == null) {
                    skippedItems += 1
                    return@forEach
                }

                val originalItemDataPlain = decryptStoredSensitiveValue(item.itemData)
                val normalizedItemDataPlain = Json.encodeToString(normalizedData)
                val itemDataChanged = normalizedItemDataPlain != originalItemDataPlain
                val normalizedItemData = if (itemDataChanged) {
                    encodeStoredSensitiveValueForRewrite(item.itemData, normalizedItemDataPlain)
                } else {
                    item.itemData
                }
                val canSafelyQueueRemoteRepair =
                    originalItemDataPlain.contains("://", ignoreCase = true) ||
                        TotpDataResolver.hasNonDefaultOtpSettings(normalizedData)

                val updatedItem = BitwardenHistoricalRepairStateHelper.applyToSecureItem(
                    candidate = item.copy(
                        itemData = normalizedItemData,
                        updatedAt = if (itemDataChanged || canSafelyQueueRemoteRepair) now else item.updatedAt
                    ),
                    shouldQueueRemoteRewrite = canSafelyQueueRemoteRepair
                )

                if (updatedItem != item) {
                    repository.updateItem(updatedItem)
                    if (itemDataChanged) {
                        normalizedTotpItems += 1
                    }
                    if (canSafelyQueueRemoteRepair && item.bitwardenCipherId != null) {
                        queuedTotpItemsForSync += 1
                    }
                }
            }

        val passwordEntries = passwordRepository.getAllPasswordEntries().first()
        passwordEntries
            .asSequence()
            .filter { it.bitwardenVaultId == vaultId && !it.isDeleted && it.authenticatorKey.isNotBlank() }
            .forEach { entry ->
                val normalizedTotp = TotpDataResolver.fromAuthenticatorKey(
                    rawKey = decryptStoredSensitiveValue(entry.authenticatorKey),
                    fallbackIssuer = entry.website.takeIf { it.isNotBlank() } ?: entry.title,
                    fallbackAccountName = entry.username.takeIf { it.isNotBlank() } ?: entry.title
                )
                if (normalizedTotp == null) {
                    skippedItems += 1
                    return@forEach
                }

                val originalPayloadPlain = decryptStoredSensitiveValue(entry.authenticatorKey)
                val normalizedPayloadPlain = TotpDataResolver.toBitwardenPayload(entry.title, normalizedTotp)
                val payloadChanged = normalizedPayloadPlain != originalPayloadPlain
                val normalizedPayload = if (payloadChanged) {
                    encodeStoredSensitiveValueForRewrite(entry.authenticatorKey, normalizedPayloadPlain)
                } else {
                    entry.authenticatorKey
                }
                val canSafelyQueueRemoteRepair =
                    originalPayloadPlain.contains("://", ignoreCase = true) ||
                        TotpDataResolver.hasNonDefaultOtpSettings(normalizedTotp)

                val updatedEntry = BitwardenHistoricalRepairStateHelper.applyToPasswordEntry(
                    candidate = entry.copy(
                        authenticatorKey = normalizedPayload,
                        updatedAt = if (payloadChanged || canSafelyQueueRemoteRepair) now else entry.updatedAt
                    ),
                    shouldQueueRemoteRewrite = canSafelyQueueRemoteRepair
                )

                if (updatedEntry != entry) {
                    passwordRepository.updatePasswordEntry(updatedEntry)
                    if (payloadChanged) {
                        normalizedPasswords += 1
                    }
                    if (canSafelyQueueRemoteRepair && entry.bitwardenCipherId != null) {
                        queuedPasswordsForSync += 1
                    }
                }
            }

        return BitwardenTotpRepairResult(
            normalizedTotpItems = normalizedTotpItems,
            queuedTotpItemsForSync = queuedTotpItemsForSync,
            normalizedPasswords = normalizedPasswords,
            queuedPasswordsForSync = queuedPasswordsForSync,
            skippedItems = skippedItems
        )
    }

    fun syncKeePassByDatabaseId(databaseId: Long) {
        syncKeePassTotp(databaseId)
    }

    suspend fun getTotpById(id: Long): SecureItem? {
        val item = repository.getItemById(id) ?: return null
        return repository.normalizeLegacyDetachedKeePassItem(item, ::hasKeePassDatabase)
    }

    private fun persistCategoryFilter(filter: TotpCategoryFilter) {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            manager.updateCategoryFilterState(
                scope = SettingsManager.CategoryFilterScope.TOTP,
                state = encodeCategoryFilter(filter)
            )
        }
    }

    private fun restoreLastCategoryFilter() {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            runCatching {
                manager.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.TOTP).first()
            }.onSuccess { state ->
                _categoryFilter.value = decodeCategoryFilter(state)
            }
        }
    }

    private suspend fun repairLegacyDetachedKeePassItems() {
        repository.repairLegacyDetachedKeePassItems(::hasKeePassDatabase)
    }

    private suspend fun hasKeePassDatabase(databaseId: Long): Boolean {
        return localKeePassDatabaseDao?.getDatabaseById(databaseId) != null
    }

    private fun encodeCategoryFilter(filter: TotpCategoryFilter): SavedCategoryFilterState = when (filter) {
        TotpCategoryFilter.All -> SavedCategoryFilterState(type = FILTER_ALL)
        TotpCategoryFilter.Local -> SavedCategoryFilterState(type = FILTER_LOCAL)
        TotpCategoryFilter.Starred -> SavedCategoryFilterState(type = FILTER_STARRED)
        TotpCategoryFilter.Uncategorized -> SavedCategoryFilterState(type = FILTER_UNCATEGORIZED)
        TotpCategoryFilter.LocalStarred -> SavedCategoryFilterState(type = FILTER_LOCAL_STARRED)
        TotpCategoryFilter.LocalUncategorized -> SavedCategoryFilterState(type = FILTER_LOCAL_UNCATEGORIZED)
        is TotpCategoryFilter.Custom -> SavedCategoryFilterState(type = FILTER_CUSTOM, primaryId = filter.categoryId)
        is TotpCategoryFilter.KeePassDatabase -> SavedCategoryFilterState(type = FILTER_KEEPASS_DATABASE, primaryId = filter.databaseId)
        is TotpCategoryFilter.KeePassGroupFilter -> SavedCategoryFilterState(
            type = FILTER_KEEPASS_GROUP,
            primaryId = filter.databaseId,
            text = filter.groupPath,
            groupUuid = filter.groupUuid
        )
        is TotpCategoryFilter.KeePassDatabaseStarred -> SavedCategoryFilterState(type = FILTER_KEEPASS_DATABASE_STARRED, primaryId = filter.databaseId)
        is TotpCategoryFilter.KeePassDatabaseUncategorized -> SavedCategoryFilterState(type = FILTER_KEEPASS_DATABASE_UNCATEGORIZED, primaryId = filter.databaseId)
        is TotpCategoryFilter.BitwardenVault -> SavedCategoryFilterState(type = FILTER_BITWARDEN_VAULT, primaryId = filter.vaultId)
        is TotpCategoryFilter.BitwardenFolderFilter -> SavedCategoryFilterState(type = FILTER_BITWARDEN_FOLDER, primaryId = filter.vaultId, text = filter.folderId)
        is TotpCategoryFilter.BitwardenVaultStarred -> SavedCategoryFilterState(type = FILTER_BITWARDEN_VAULT_STARRED, primaryId = filter.vaultId)
        is TotpCategoryFilter.BitwardenVaultUncategorized -> SavedCategoryFilterState(type = FILTER_BITWARDEN_VAULT_UNCATEGORIZED, primaryId = filter.vaultId)
        is TotpCategoryFilter.MdbxDatabase -> SavedCategoryFilterState(type = FILTER_MDBX_DATABASE, primaryId = filter.databaseId)
    }

    private fun decodeCategoryFilter(state: SavedCategoryFilterState): TotpCategoryFilter {
        return when (state.type.lowercase(Locale.ROOT)) {
            FILTER_ALL -> TotpCategoryFilter.All
            FILTER_LOCAL -> TotpCategoryFilter.Local
            FILTER_STARRED -> TotpCategoryFilter.Starred
            FILTER_UNCATEGORIZED -> TotpCategoryFilter.Uncategorized
            FILTER_LOCAL_STARRED -> TotpCategoryFilter.LocalStarred
            FILTER_LOCAL_UNCATEGORIZED -> TotpCategoryFilter.LocalUncategorized
            FILTER_CUSTOM -> state.primaryId?.let { TotpCategoryFilter.Custom(it) } ?: TotpCategoryFilter.All
            FILTER_KEEPASS_DATABASE -> state.primaryId?.let { TotpCategoryFilter.KeePassDatabase(it) } ?: TotpCategoryFilter.All
            FILTER_KEEPASS_GROUP -> {
                val databaseId = state.primaryId
                val groupPath = state.text
                if (databaseId != null && !groupPath.isNullOrBlank()) {
                    TotpCategoryFilter.KeePassGroupFilter(databaseId, groupPath, state.groupUuid)
                } else {
                    TotpCategoryFilter.All
                }
            }
            FILTER_KEEPASS_DATABASE_STARRED -> state.primaryId?.let { TotpCategoryFilter.KeePassDatabaseStarred(it) } ?: TotpCategoryFilter.All
            FILTER_KEEPASS_DATABASE_UNCATEGORIZED -> state.primaryId?.let { TotpCategoryFilter.KeePassDatabaseUncategorized(it) } ?: TotpCategoryFilter.All
            FILTER_BITWARDEN_VAULT -> state.primaryId?.let { TotpCategoryFilter.BitwardenVault(it) } ?: TotpCategoryFilter.All
            FILTER_BITWARDEN_FOLDER -> {
                val vaultId = state.primaryId
                val folderId = state.text
                if (vaultId != null && !folderId.isNullOrBlank()) TotpCategoryFilter.BitwardenFolderFilter(folderId, vaultId) else TotpCategoryFilter.All
            }
            FILTER_BITWARDEN_VAULT_STARRED -> state.primaryId?.let { TotpCategoryFilter.BitwardenVaultStarred(it) } ?: TotpCategoryFilter.All
            FILTER_BITWARDEN_VAULT_UNCATEGORIZED -> state.primaryId?.let { TotpCategoryFilter.BitwardenVaultUncategorized(it) } ?: TotpCategoryFilter.All
            FILTER_MDBX_DATABASE -> state.primaryId?.let { TotpCategoryFilter.MdbxDatabase(it) } ?: TotpCategoryFilter.All
            else -> TotpCategoryFilter.All
        }
    }

    private fun syncKeePassTotp(databaseId: Long) {
        val requestId = SyncDiagnostics.nextTaskId("kp-totp")
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = requestId,
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.TOTP)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncKeePassTotpNow(databaseId, requestId)
            }
        }
    }

    private suspend fun syncKeePassTotpNow(databaseId: Long, taskId: String) {
        val target = "keepass_compat:totp:$databaseId"
        val trigger = "TOTP_FILTER_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            val bridge = keepassBridge ?: run {
                SyncDiagnostics.skipped(taskId, target, trigger, "bridge_unavailable", startedAt)
                return
            }
            val decision = bridge.legacyProjectionRefreshDecision(
                databaseId,
                takagi.ru.monica.keepass.KeePassProjectionKind.TOTP
            ).getOrThrow()
            if (!decision.needsRefresh) {
                SyncDiagnostics.skipped(taskId, target, trigger, "native_revision_unchanged", startedAt)
                return
            }
            val snapshots = bridge
                .readLegacySecureItems(databaseId, setOf(ItemType.TOTP))
                ?.getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "bridge_or_read_unavailable", startedAt)
                    return
                }

            val existingTotp = repository.getItemsByType(ItemType.TOTP).first()
            snapshots.forEach { snapshot ->
                val incoming = snapshot.item
                val existingByUuid = incoming.keepassEntryUuid
                    ?.takeIf { it.isNotBlank() }
                    ?.let { repository.getItemByKeePassUuid(databaseId, it) }
                val existingBySource = snapshot.sourceMonicaId
                    ?.takeIf { it > 0 }
                    ?.let { sourceId -> repository.getItemById(sourceId) }
                    ?.takeIf { it.itemType == ItemType.TOTP }
                val incomingIdentityKey = parseStoredTotpData(incoming)?.let(::buildTotpIdentityKey)
                val existing = KeePassTotpProjectionMatcher.findExistingProjection(
                    databaseId = databaseId,
                    incoming = incoming,
                    existingTotp = existingTotp,
                    existingByUuid = existingByUuid,
                    existingBySource = existingBySource,
                    incomingIdentityKey = incomingIdentityKey
                ) { candidate ->
                    parseStoredTotpData(candidate)?.let(::buildTotpIdentityKey)
                }

                if (existing == null) {
                    repository.insertItem(incoming)
                } else {
                    val isInRecycleBin = snapshot.isInRecycleBin
                    val updated = existing.copy(
                        title = incoming.title,
                        notes = incoming.notes,
                        itemData = incoming.itemData,
                        isFavorite = incoming.isFavorite,
                        imagePaths = incoming.imagePaths,
                        keepassDatabaseId = incoming.keepassDatabaseId,
                        keepassGroupPath = incoming.keepassGroupPath,
                        keepassEntryUuid = incoming.keepassEntryUuid,
                        keepassGroupUuid = incoming.keepassGroupUuid,
                        isDeleted = isInRecycleBin,
                        deletedAt = if (isInRecycleBin) (existing.deletedAt ?: Date()) else null,
                        updatedAt = Date()
                    )
                    if (!existing.matchesKeePassSecureItemImport(updated)) {
                        repository.updateItem(updated)
                    }
                }
            }
            bridge.markLegacyProjectionIndexed(
                databaseId,
                decision.revisionToken,
                setOf(takagi.ru.monica.keepass.KeePassProjectionKind.TOTP)
            )
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "items=${snapshots.size}"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            throw error
        }
    }

    private fun SecureItem.matchesKeePassSecureItemImport(imported: SecureItem): Boolean {
        return copy(itemData = "", updatedAt = imported.updatedAt) == imported.copy(itemData = "") &&
            decryptStoredSensitiveValue(itemData) == decryptStoredSensitiveValue(imported.itemData)
    }
    
    /**
     * 快速添加TOTP（从底部导航栏快速添加）
     */
    fun quickAddTotp(name: String, secret: String) {
        if (name.isBlank() || secret.isBlank()) return
        val totpData = TotpData(
            secret = secret.replace(" ", "").uppercase(),
            issuer = name,
            accountName = name
        )
        saveTotpItem(
            id = null,
            title = name,
            notes = "",
            totpData = totpData
        )
    }
    
    /**
     * 根据密钥查找现有的TOTP项目
     */
    fun findTotpBySecret(secret: String): SecureItem? {
        val targetKey = buildTotpIdentityKeyFromRawKey(secret) ?: return null
        return totpItems.value.find { item ->
            parseStoredTotpData(item)?.let { data ->
                buildTotpIdentityKey(data) == targetKey
            } ?: false
        }
    }

    fun findTotpByData(
        data: TotpData,
        targets: List<StorageTarget>
    ): SecureItem? {
        val targetScopes = targets.map { it.storageScopeKey() }.toSet()
        val targetKey = buildTotpIdentityKey(data)
        val candidates = allTotpItems.value.ifEmpty { totpItems.value }
        return candidates.firstOrNull { item ->
            !item.isDeleted &&
                (targetScopes.isEmpty() || item.toStorageTarget().storageScopeKey() in targetScopes) &&
                parseStoredTotpData(item)?.let(::buildTotpIdentityKey) == targetKey
        }
    }

    /**
     * Save the authenticator owned by a password row.
     *
     * This must use persisted TOTP rows, not [totpItems], because [totpItems] is filtered by the
     * current authenticator tab UI and also contains virtual password-derived entries.
     */
    fun savePasswordBoundTotp(
        passwordId: Long,
        title: String,
        notes: String = "",
        totpData: TotpData,
        isFavorite: Boolean = false,
        preferredTotpId: Long? = null
    ) {
        viewModelScope.launch {
            savePasswordBoundTotpInternal(
                passwordId = passwordId,
                title = title,
                notes = notes,
                totpData = totpData,
                isFavorite = isFavorite,
                preferredTotpId = preferredTotpId
            )
        }
    }

    fun savePasswordBoundTotps(
        passwordIds: List<Long>,
        title: String,
        notes: String = "",
        totpData: TotpData,
        isFavorite: Boolean = false,
        preferredTotpId: Long? = null
    ) {
        viewModelScope.launch {
            passwordIds
                .filter { it > 0L }
                .distinct()
                .forEachIndexed { index, passwordId ->
                    savePasswordBoundTotpInternal(
                        passwordId = passwordId,
                        title = title,
                        notes = notes,
                        totpData = totpData,
                        isFavorite = isFavorite,
                        preferredTotpId = preferredTotpId.takeIf { index == 0 }
                    )
                }
        }
    }

    private suspend fun savePasswordBoundTotpInternal(
        passwordId: Long,
        title: String,
        notes: String,
        totpData: TotpData,
        isFavorite: Boolean,
        preferredTotpId: Long?
    ): Boolean {
        return try {
            val normalizedInput = TotpDataResolver.normalizeTotpData(totpData).copy(
                boundPasswordId = passwordId
            )
            val identityKey = buildTotpIdentityKey(normalizedInput)
            val boundPassword = passwordRepository.getPasswordEntryById(passwordId)
            val boundTargetScopeKey = boundPassword?.toStorageTarget()?.storageScopeKey()
            val existingStoredTotps = repository.getItemsByType(ItemType.TOTP).first()
            val activeStoredItems = existingStoredTotps.mapNotNull { item ->
                if (item.isDeleted) return@mapNotNull null
                val data = parseStoredTotpData(item)
                    ?: return@mapNotNull null
                item to data
            }
            fun Pair<SecureItem, TotpData>.isInBoundPasswordStorage(): Boolean {
                return boundTargetScopeKey == null ||
                    first.toStorageTarget().storageScopeKey() == boundTargetScopeKey
            }

            val activeBoundItems = activeStoredItems.mapNotNull { (item, data) ->
                if (data.boundPasswordId == passwordId) {
                    item to data
                } else {
                    null
                }
            }
            val selectedSourceItem = activeStoredItems
                .firstOrNull { (item, _) -> preferredTotpId != null && item.id == preferredTotpId }
            val preferredItem = selectedSourceItem ?: activeBoundItems.firstOrNull {
                    it.isInBoundPasswordStorage() && buildTotpIdentityKey(it.second) == identityKey
                }
                ?: activeBoundItems.firstOrNull { it.isInBoundPasswordStorage() }
            val metadataSource = preferredItem
            val preserveSelectedSourceStorage = selectedSourceItem?.first?.id == preferredItem?.first?.id
            val resolvedCategoryId = if (preserveSelectedSourceStorage) {
                preferredItem?.first?.categoryId
            } else {
                boundPassword?.categoryId ?: normalizedInput.categoryId
            }
            val resolvedKeepassDatabaseId = if (preserveSelectedSourceStorage) {
                preferredItem?.first?.keepassDatabaseId
            } else {
                boundPassword?.keepassDatabaseId ?: normalizedInput.keepassDatabaseId
            }
            val resolvedData = normalizedInput.copy(
                categoryId = resolvedCategoryId,
                keepassDatabaseId = resolvedKeepassDatabaseId
            )

            val savedItemId = saveTotpItemInternal(
                id = preferredItem?.first?.id,
                title = metadataSource?.first?.title ?: title,
                notes = metadataSource?.first?.notes ?: notes,
                totpData = resolvedData,
                isFavorite = metadataSource?.first?.isFavorite ?: isFavorite,
                categoryId = resolvedCategoryId,
                keepassDatabaseId = resolvedKeepassDatabaseId,
                keepassGroupPath = if (preserveSelectedSourceStorage) {
                    preferredItem?.first?.keepassGroupPath
                } else {
                    boundPassword?.keepassGroupPath
                },
                mdbxDatabaseId = if (preserveSelectedSourceStorage) {
                    preferredItem?.first?.mdbxDatabaseId
                } else {
                    boundPassword?.mdbxDatabaseId
                },
                mdbxFolderId = if (preserveSelectedSourceStorage) {
                    preferredItem?.first?.mdbxFolderId
                } else {
                    boundPassword?.mdbxFolderId
                },
                bitwardenVaultId = preferredItem?.first?.bitwardenVaultId
                    .takeIf { preserveSelectedSourceStorage },
                bitwardenFolderId = preferredItem?.first?.bitwardenFolderId
                    .takeIf { preserveSelectedSourceStorage },
                followBoundPasswordStorage = !preserveSelectedSourceStorage
            )
            if (savedItemId == null) {
                Log.e(
                    "TotpViewModel",
                    "savePasswordBoundTotp failed to persist bound item passwordId=$passwordId totpId=${preferredItem?.first?.id}"
                )
                return false
            }

            removeOtherBoundTotpsForPassword(
                keepItemId = savedItemId,
                passwordId = passwordId
            )
            true
        } catch (e: Exception) {
            Log.e("TotpViewModel", "savePasswordBoundTotp failed for passwordId=$passwordId", e)
            false
        }
    }

    /**
     * 根据ID获取TOTP项目
     */
    suspend fun getTotpItemById(id: Long): SecureItem? {
        val item = repository.getItemById(id) ?: return null
        return repository.normalizeLegacyDetachedKeePassItem(item, ::hasKeePassDatabase)
    }
    
    /**
     * 保存TOTP项目
     */
    fun saveTotpItem(
        id: Long?,
        title: String,
        notes: String,
        totpData: TotpData,
        isFavorite: Boolean = false,
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null
    ) {
        viewModelScope.launch {
            try {
                saveTotpItemInternal(
                    id = id,
                    title = title,
                    notes = notes,
                    totpData = totpData,
                    isFavorite = isFavorite,
                    categoryId = categoryId,
                    keepassDatabaseId = keepassDatabaseId,
                    keepassGroupPath = keepassGroupPath,
                    mdbxDatabaseId = mdbxDatabaseId,
                    mdbxFolderId = mdbxFolderId,
                    bitwardenVaultId = bitwardenVaultId,
                    bitwardenFolderId = bitwardenFolderId,
                    followBoundPasswordStorage = totpData.boundPasswordId != null
                )
            } catch (e: Exception) {
                Log.e(
                    "TotpViewModel",
                    "saveTotpItem failed id=$id categoryId=$categoryId keepassDatabaseId=$keepassDatabaseId mdbxDatabaseId=$mdbxDatabaseId bitwardenVaultId=$bitwardenVaultId error=${e::class.java.simpleName}: ${e.message}",
                    e
                )
            }
        }
    }

    fun saveTotpAcrossTargets(
        id: Long?,
        title: String,
        notes: String,
        totpData: TotpData,
        isFavorite: Boolean = false,
        targets: List<StorageTarget>,
        onFailure: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val saved = try {
                val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
                if (distinctTargets.isEmpty()) {
                    Log.w("TotpViewModel", "saveTotpAcrossTargets blocked because target list is empty id=$id")
                    onFailure("未选择保存位置")
                    false
                } else {
                    requireKeePassKeyFilesAvailable(distinctTargets)
                    val existingItem = id?.let { repository.getItemById(it) }?.takeIf { it.itemType == ItemType.TOTP }
                    val selectedTargetKeys = distinctTargets.map(StorageTarget::stableKey).toSet()
                    val currentTarget = existingItem
                        ?.toStorageTarget()
                        ?.takeIf { it.stableKey in selectedTargetKeys }
                        ?: distinctTargets.first()
                    val replicaGroupId = existingItem?.replicaGroupId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                    val existingReplicasByKey = if (existingItem != null) {
                        repository.getAllItems().first()
                            .asSequence()
                            .filter {
                                it.itemType == ItemType.TOTP &&
                                    it.replicaGroupId == replicaGroupId &&
                                    it.id != existingItem.id &&
                                    !it.isDeleted
                            }
                            .associateBy { it.toStorageTarget().stableKey }
                    } else {
                        emptyMap()
                    }

                    suspend fun saveIntoTarget(target: StorageTarget, targetId: Long?): Boolean {
                        val savedId = when (target) {
                            is StorageTarget.MonicaLocal -> saveTotpItemInternal(
                                id = targetId,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                categoryId = target.categoryId,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                followBoundPasswordStorage = false,
                                replicaGroupId = replicaGroupId
                            )
                            is StorageTarget.KeePass -> saveTotpItemInternal(
                                id = targetId,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                categoryId = null,
                                keepassDatabaseId = target.databaseId,
                                keepassGroupPath = target.groupPath,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                followBoundPasswordStorage = false,
                                replicaGroupId = replicaGroupId
                            )
                            is StorageTarget.Mdbx -> saveTotpItemInternal(
                                id = targetId,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                categoryId = null,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                mdbxDatabaseId = target.databaseId,
                                mdbxFolderId = target.folderId,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                followBoundPasswordStorage = false,
                                replicaGroupId = replicaGroupId
                            )
                            is StorageTarget.Bitwarden -> saveTotpItemInternal(
                                id = targetId,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                categoryId = null,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null,
                                bitwardenVaultId = target.vaultId,
                                bitwardenFolderId = target.folderId,
                                followBoundPasswordStorage = false,
                                replicaGroupId = replicaGroupId
                            )
                        }
                        return savedId != null
                    }

                    val currentSaved = saveIntoTarget(currentTarget, existingItem?.id)
                    if (!currentSaved) {
                        Log.e(
                            "TotpViewModel",
                            "saveTotpAcrossTargets failed current target=${currentTarget.stableKey} id=$id targets=${distinctTargets.map(StorageTarget::stableKey)}"
                        )
                        onFailure("无法保存到所选存储位置")
                        false
                    } else {
                        var allTargetsSaved = true
                        distinctTargets
                            .filter { it.stableKey != currentTarget.stableKey }
                            .forEach { target ->
                                val targetSaved = saveIntoTarget(target, targetId = existingReplicasByKey[target.stableKey]?.id)
                                if (!targetSaved) {
                                    allTargetsSaved = false
                                    Log.e("TotpViewModel", "saveTotpAcrossTargets skipped failed target")
                                }
                            }

                        if (existingItem != null && distinctTargets.size == 1 &&
                            currentTarget.stableKey != existingItem.toStorageTarget().stableKey) {
                            repository.getAllItems().first()
                            .filter {
                                it.itemType == ItemType.TOTP &&
                                    it.replicaGroupId == replicaGroupId &&
                                    it.id != existingItem?.id &&
                                    !it.isDeleted &&
                                    it.toStorageTarget().stableKey !in selectedTargetKeys
                            }
                            .forEach { repository.deleteItemById(it.id) }
                        }
                        allTargetsSaved
                    }
                }
            } catch (e: Exception) {
                val failureMessage = e.toKeePassOperationException().message
                Log.e(
                    "TotpViewModel",
                    "saveTotpAcrossTargets crashed id=$id targets=${targets.map(StorageTarget::stableKey)} error=${e::class.java.simpleName}: ${e.message}",
                    e
                )
                onFailure(failureMessage)
                false
            }
            onComplete(saved)
        }
    }

    private suspend fun requireKeePassKeyFilesAvailable(targets: List<StorageTarget>) {
        val dao = localKeePassDatabaseDao ?: return
        val appContext = applicationContext ?: return
        val resolver = appContext.contentResolver
        val databaseIds = targets
            .filterIsInstance<StorageTarget.KeePass>()
            .map(StorageTarget.KeePass::databaseId)
            .distinct()
        if (databaseIds.isEmpty()) return

        withContext(Dispatchers.IO) {
            databaseIds.forEach { databaseId ->
                val keyFileUri = dao.getDatabaseById(databaseId)
                    ?.keyFileUri
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                resolver.readKeePassKeyFileBytes(
                    uri = Uri.parse(keyFileUri),
                    unavailableMessage = appContext.getString(
                        takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
                    )
                )
            }
        }
    }

    fun saveTotpMigrationBatch(
        items: List<TotpParseResult>,
        targets: List<StorageTarget>,
        onComplete: (TotpMigrationSaveResult) -> Unit
    ) {
        viewModelScope.launch {
            val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
            val plan = planTotpMigrationBatch(items)
            if (distinctTargets.isEmpty() || plan is TotpMigrationBatchPlan.Rejected) {
                onComplete(
                    TotpMigrationSaveResult(
                        importedCount = 0,
                        duplicateCount = 0,
                        failedCount = items.size,
                        validationRejected = true
                    )
                )
                return@launch
            }

            val readyPlan = plan as TotpMigrationBatchPlan.Ready

            var importedCount = 0
            var failedCount = 0
            readyPlan.items.forEach { item ->
                val title = item.label
                    .ifBlank { item.totpData.issuer }
                    .ifBlank { item.totpData.accountName }
                val saved = saveTotpAcrossTargetsAwait(
                    title = title,
                    totpData = item.totpData,
                    targets = distinctTargets
                )
                if (saved) {
                    importedCount++
                } else {
                    failedCount++
                }
            }

            onComplete(
                TotpMigrationSaveResult(
                    importedCount = importedCount,
                    duplicateCount = readyPlan.duplicateCount,
                    failedCount = failedCount,
                    validationRejected = false
                )
            )
        }
    }

    private suspend fun saveTotpAcrossTargetsAwait(
        title: String,
        totpData: TotpData,
        targets: List<StorageTarget>
    ): Boolean = suspendCancellableCoroutine { continuation ->
        saveTotpAcrossTargets(
            id = null,
            title = title,
            notes = "",
            totpData = totpData,
            isFavorite = false,
            targets = targets,
            onComplete = { saved ->
                if (continuation.isActive) {
                    continuation.resume(saved)
                }
            }
        )
    }

    private suspend fun saveTotpItemInternal(
        id: Long?,
        title: String,
        notes: String,
        totpData: TotpData,
        isFavorite: Boolean,
        categoryId: Long?,
        keepassDatabaseId: Long?,
        keepassGroupPath: String?,
        mdbxDatabaseId: Long?,
        mdbxFolderId: String?,
        bitwardenVaultId: Long?,
        bitwardenFolderId: String?,
        followBoundPasswordStorage: Boolean,
        replicaGroupId: String? = null
    ): Long? {
        val existingItem = if (id != null && id > 0) repository.getItemById(id) else null
        if (id != null && id > 0 && existingItem == null) {
            Log.e("TotpViewModel", "saveTotpItemInternal failed because item does not exist id=$id")
            return null
        }
        val previousTotpData = existingItem?.let(::parseStoredTotpData)
        val previousBoundId = previousTotpData?.boundPasswordId
        val previousSecret = previousTotpData?.secret ?: ""

        val shouldFollowBoundPassword = followBoundPasswordStorage && totpData.boundPasswordId != null
        val boundPassword = if (shouldFollowBoundPassword) {
            totpData.boundPasswordId?.let { passwordRepository.getPasswordEntryById(it) }
        } else {
            null
        }
        val resolvedKeepassDatabaseId = if (shouldFollowBoundPassword) {
            boundPassword?.keepassDatabaseId ?: keepassDatabaseId
        } else {
            keepassDatabaseId
        }
        val resolvedMdbxDatabaseId = if (shouldFollowBoundPassword) {
            boundPassword?.mdbxDatabaseId ?: mdbxDatabaseId
        } else {
            mdbxDatabaseId
        }
        val resolvedMdbxFolderId = if (resolvedMdbxDatabaseId == null) {
            null
        } else if (shouldFollowBoundPassword) {
            boundPassword?.mdbxFolderId ?: mdbxFolderId
        } else {
            mdbxFolderId
        }
        val resolvedKeepassGroupPath = when {
            resolvedKeepassDatabaseId == null -> null
            shouldFollowBoundPassword -> boundPassword?.keepassGroupPath
            else -> keepassGroupPath ?: existingItem?.keepassGroupPath
        }
        val keepassIdentity = resolveKeePassMutationIdentity(
            existingItem = existingItem,
            targetDatabaseId = resolvedKeepassDatabaseId,
            requestedGroupPath = resolvedKeepassGroupPath
        )
        val updatedTotpData = totpData.copy(
            categoryId = categoryId,
            keepassDatabaseId = resolvedKeepassDatabaseId
        )
        val itemDataJson = Json.encodeToString(updatedTotpData)
        val storedItemData = encodeStoredSensitiveValueForNewWrite(itemDataJson)

        if (
            shouldFollowBoundPassword &&
            boundPassword?.mdbxDatabaseId != null &&
            existingItem != null &&
            existingItem.mdbxDatabaseId != boundPassword.mdbxDatabaseId
        ) {
            val copy = repository.ensureMdbxCopyForBinding(
                source = existingItem,
                databaseId = boundPassword.mdbxDatabaseId,
                title = title,
                notes = notes,
                itemData = storedItemData,
                imagePaths = existingItem.imagePaths,
                isFavorite = isFavorite,
                categoryId = null,
                mdbxFolderId = boundPassword.mdbxFolderId
            )
            val authenticatorPayload = TotpDataResolver.toBitwardenPayload(title, updatedTotpData)
            if (authenticatorPayload.isNotBlank()) {
                updatePasswordAuthenticatorKeyForStorage(boundPassword.id, authenticatorPayload)
            }
            return copy.id
        }

        val resolvedBitwardenVaultId = if (shouldFollowBoundPassword) null else bitwardenVaultId
        val resolvedBitwardenFolderId = if (shouldFollowBoundPassword) null else bitwardenFolderId
        val resolvedReplicaGroupId = replicaGroupId ?: existingItem?.replicaGroupId
        val transition = resolveBitwardenTransition(
            existingItem = existingItem,
            targetVaultId = resolvedBitwardenVaultId,
            targetFolderId = resolvedBitwardenFolderId,
            forcePendingWhenKeepingCipher = !shouldFollowBoundPassword &&
                resolvedBitwardenVaultId != null &&
                existingItem?.bitwardenVaultId == resolvedBitwardenVaultId &&
                !existingItem?.bitwardenCipherId.isNullOrBlank(),
            abortOnQueueFailure = true
        ) ?: run {
            Log.e(
                "TotpViewModel",
                "saveTotpItemInternal failed because Bitwarden transition could not be resolved id=$id bitwardenVaultId=$resolvedBitwardenVaultId"
            )
            return null
        }

        val item = if (id != null && id > 0) {
            existingItem?.copy(
                title = title,
                notes = notes,
                itemData = storedItemData,
                isFavorite = isFavorite,
                categoryId = categoryId,
                keepassDatabaseId = resolvedKeepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                mdbxDatabaseId = resolvedMdbxDatabaseId,
                mdbxFolderId = resolvedMdbxFolderId,
                bitwardenVaultId = resolvedBitwardenVaultId,
                bitwardenFolderId = resolvedBitwardenFolderId,
                bitwardenCipherId = transition.cipherId,
                bitwardenRevisionDate = transition.revisionDate,
                bitwardenLocalModified = transition.localModified,
                syncStatus = transition.syncStatus,
                replicaGroupId = resolvedReplicaGroupId,
                updatedAt = Date()
            ) ?: run {
                Log.e(
                    "TotpViewModel",
                    "saveTotpItemInternal failed while updating missing item id=$id"
                )
                return null
            }
        } else {
            SecureItem(
                itemType = ItemType.TOTP,
                title = title,
                notes = notes,
                itemData = storedItemData,
                isFavorite = isFavorite,
                categoryId = categoryId,
                keepassDatabaseId = resolvedKeepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                mdbxDatabaseId = resolvedMdbxDatabaseId,
                mdbxFolderId = resolvedMdbxFolderId,
                bitwardenVaultId = resolvedBitwardenVaultId,
                bitwardenFolderId = resolvedBitwardenFolderId,
                bitwardenCipherId = transition.cipherId,
                bitwardenRevisionDate = transition.revisionDate,
                bitwardenLocalModified = transition.localModified,
                syncStatus = transition.syncStatus,
                createdAt = Date(),
                updatedAt = Date(),
                imagePaths = "",
                replicaGroupId = resolvedReplicaGroupId
            )
        }

        val savedItemId = if (id != null && id > 0) {
            val existing = repository.getItemById(id)
            val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
                existingItem = existing ?: existingItem,
                updatedItem = item,
                persistUpdate = { persistedItem ->
                    repository.updateItem(persistedItem)
                }
            )
            if (keepassSync.isFailure) {
                Log.e(
                    "TotpViewModel",
                    "KeePass TOTP update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                )
                return null
            }
            requestBitwardenMutationSync(resolvedBitwardenVaultId)
            if (existing != null) {
                val changes = mutableListOf<FieldChange>()
                if (existing.title != title) {
                    changes.add(FieldChange("标题", existing.title, title))
                }
                if (existing.notes != notes) {
                    changes.add(FieldChange("备注", existing.notes, notes))
                }
                OperationLogger.logUpdate(
                    itemType = OperationLogItemType.TOTP,
                    itemId = id,
                    itemTitle = title,
                    changes = if (changes.isEmpty()) {
                        listOf(FieldChange("更新", "编辑于", java.text.SimpleDateFormat("HH:mm").format(Date())))
                    } else {
                        changes
                    }
                )
            }
            item.id
        } else {
            val newId = keepassSecureItemCreateExecutor.create(
                item = item,
                insertItem = repository::insertItem,
                rollbackItem = repository::deleteItemById
            ) ?: run {
                Log.e(
                    "TotpViewModel",
                    "saveTotpItemInternal failed while creating item keepassDatabaseId=$resolvedKeepassDatabaseId mdbxDatabaseId=$resolvedMdbxDatabaseId bitwardenVaultId=$resolvedBitwardenVaultId"
                )
                return null
            }
            requestBitwardenMutationSync(resolvedBitwardenVaultId)
            OperationLogger.logCreate(
                itemType = OperationLogItemType.TOTP,
                itemId = newId,
                itemTitle = title
            )
            newId
        }

        val boundId = updatedTotpData.boundPasswordId
        val authenticatorPayload = TotpDataResolver.toBitwardenPayload(title, updatedTotpData)
        if (boundId != null && authenticatorPayload.isNotBlank()) {
            updatePasswordAuthenticatorKeyForStorage(boundId, authenticatorPayload)
            passwordRepository.getPasswordEntryById(boundId)?.bitwardenVaultId?.let(::requestBitwardenMutationSync)
        }

        if (previousBoundId != null && previousBoundId != boundId && previousSecret.isNotBlank()) {
            val previousPassword = passwordRepository.getPasswordEntryById(previousBoundId)
            val previousPasswordKey = previousPassword
                ?.authenticatorKey
                ?.let(::buildTotpIdentityKeyFromRawKey)
            val previousTotpKey = buildTotpIdentityKeyFromRawKey(previousSecret)
            if (previousPasswordKey != null && previousPasswordKey == previousTotpKey) {
                updatePasswordAuthenticatorKeyForStorage(previousBoundId, "")
                previousPassword.bitwardenVaultId?.let(::requestBitwardenMutationSync)
            }
        }

        return savedItemId
    }

    private fun resolveKeePassMutationIdentity(
        existingItem: SecureItem?,
        targetDatabaseId: Long?,
        requestedGroupPath: String?
    ): KeePassMutationIdentity {
        if (targetDatabaseId == null) {
            return KeePassMutationIdentity(
                groupPath = null,
                entryUuid = null,
                groupUuid = null
            )
        }

        val sameDatabase = existingItem?.keepassDatabaseId == targetDatabaseId
        val resolvedGroupPath = requestedGroupPath ?: if (sameDatabase) existingItem?.keepassGroupPath else null
        val groupUnchanged = sameDatabase && resolvedGroupPath == existingItem?.keepassGroupPath

        return KeePassMutationIdentity(
            groupPath = resolvedGroupPath,
            entryUuid = KeePassCrossDatabaseTransfer.secureItemTargetEntryUuid(
                item = existingItem,
                databaseId = targetDatabaseId,
                groupPath = resolvedGroupPath
            ),
            groupUuid = if (groupUnchanged) existingItem?.keepassGroupUuid else null
        )
    }

    /**
     * 解绑指定密码条目对应的验证器（不删除验证器本身）
     * 当密码页清空密钥时，仅解除绑定
     */
    fun unbindTotpFromPassword(passwordId: Long, secret: String? = null) {
        viewModelScope.launch {
            try {
                val items = repository.getItemsByType(ItemType.TOTP).first()
                val targetKey = secret
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::buildTotpIdentityKeyFromRawKey)
                val target = items.firstOrNull { item ->
                    val data = parseStoredTotpData(item)
                    data?.let { parsed ->
                        parsed.boundPasswordId == passwordId &&
                            (targetKey == null || buildTotpIdentityKey(parsed) == targetKey)
                    } ?: false
                }

                if (target != null) {
                    val data = parseStoredTotpData(target) ?: return@launch
                    val updatedData = data.copy(boundPasswordId = null)
                    val updatedItemDataPlain = Json.encodeToString(updatedData)
                    repository.updateItem(
                        target.copy(
                            itemData = encodeStoredSensitiveValueForRewrite(
                                target.itemData,
                                updatedItemDataPlain
                            ),
                            updatedAt = Date()
                        )
                    )
                }
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun removeOtherBoundTotpsForPassword(
        keepItemId: Long?,
        passwordId: Long
    ) {
        if (keepItemId == null) return
        val items = repository.getItemsByType(ItemType.TOTP).first()
        val matchingItems = items.mapNotNull { item ->
            if (item.id == keepItemId || item.isDeleted) return@mapNotNull null
            val data = parseStoredTotpData(item)
                ?: return@mapNotNull null
            if (data.boundPasswordId == passwordId) {
                item
            } else {
                null
            }
        }

        matchingItems.forEach { duplicate ->
            Log.w(
                "TotpViewModel",
                "Soft-deleting extra bound TOTP id=${duplicate.id} for passwordId=$passwordId"
            )
            repository.updateItem(
                duplicate.copy(
                    isDeleted = true,
                    deletedAt = Date(),
                    updatedAt = Date()
                )
            )
        }
    }
    
    /**
     * 删除TOTP项目
     * @param item 要删除的项目
     * @param softDelete 是否软删除（移入回收站），默认为 true
     */
    fun deleteTotpItems(items: List<SecureItem>, softDelete: Boolean = true) {
        val deletingItemIds = items.mapTo(mutableSetOf()) { it.id }
        items.forEach { item ->
            deleteTotpItem(
                item = item,
                softDelete = softDelete,
                deletingItemIds = deletingItemIds
            )
        }
    }

    fun deleteTotpItem(
        item: SecureItem,
        softDelete: Boolean = true,
        deletingItemIds: Set<Long> = emptySet()
    ) {
        viewModelScope.launch {
            val totpData = parseStoredTotpData(item)

            if (totpData?.boundPasswordId != null && totpData.secret.isNotBlank()) {
                val boundId = totpData.boundPasswordId
                val password = passwordRepository.getPasswordEntryById(boundId)
                if (password != null) {
                    val passwordKey = buildTotpIdentityKeyFromRawKey(password.authenticatorKey)
                    val itemKey = buildTotpIdentityKey(totpData)
                    val hasEquivalentBoundItem = repository.getItemsByType(ItemType.TOTP)
                        .first()
                        .any { candidate ->
                            if (
                                candidate.id == item.id ||
                                candidate.id in deletingItemIds ||
                                candidate.isDeleted
                            ) {
                                return@any false
                            }
                            val candidateData = parseStoredTotpData(candidate) ?: return@any false
                            candidateData.boundPasswordId == boundId &&
                                buildTotpIdentityKey(candidateData) == itemKey
                        }
                    if (passwordKey != null && passwordKey == itemKey && !hasEquivalentBoundItem) {
                        if (password.bitwardenVaultId != null && password.bitwardenCipherId != null) {
                            // For Bitwarden-linked passwords, mark as locally modified so sync can clear remote login.totp.
                            passwordRepository.updatePasswordEntry(
                                password.copy(
                                    authenticatorKey = "",
                                    bitwardenLocalModified = true,
                                    updatedAt = Date()
                                )
                            )
                            requestBitwardenMutationSync(password.bitwardenVaultId)
                        } else {
                            updatePasswordAuthenticatorKeyForStorage(boundId, "")
                        }
                    }
                }
            }

            // Virtual TOTP items are derived from password.authenticatorKey and are not persisted in secure_items.
            if (item.id <= 0) {
                return@launch
            }

            val vaultId = item.bitwardenVaultId
            val cipherId = item.bitwardenCipherId
            val isBitwardenCipher = vaultId != null && !cipherId.isNullOrBlank()

            if (isBitwardenCipher) {
                val queueResult = bitwardenRepository?.queueCipherDelete(
                    vaultId = vaultId!!,
                    cipherId = cipherId!!,
                    entryId = item.id,
                    itemType = BitwardenPendingOperation.ITEM_TYPE_TOTP
                )
                if (queueResult?.isFailure == true) {
                    Log.e("TotpViewModel", "Queue Bitwarden delete failed: ${queueResult.exceptionOrNull()?.message}")
                    return@launch
                }
            }

            if (!softDelete || isBitwardenCipher) {
                if (!keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = softDelete || isBitwardenCipher)) {
                    Log.e("TotpViewModel", "KeePass delete failed for totp id=${item.id}")
                    return@launch
                }
            }

            if (isBitwardenCipher) {
                repository.updateItem(
                    item.copy(
                        isDeleted = true,
                        deletedAt = Date(),
                        updatedAt = Date(),
                        bitwardenLocalModified = true
                    )
                )
                requestBitwardenMutationSync(vaultId)
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.TOTP,
                    itemId = item.id,
                    itemTitle = item.title,
                    detail = "移入回收站（待同步删除）"
                )
                return@launch
            }

            if (softDelete) {
                // 软删除：移动到回收站
                repository.softDeleteItem(item)
                // 记录移入回收站操作
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.TOTP,
                    itemId = item.id,
                    itemTitle = item.title,
                    detail = "移入回收站"
                )

                if (!isBitwardenCipher && item.keepassDatabaseId != null) {
                    viewModelScope.launch keepassDeleteSync@{
                        if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = true)) {
                            Log.i("TotpViewModel", "KeePass trash delete synced for totp id=${item.id}")
                            return@keepassDeleteSync
                        }

                        Log.e("TotpViewModel", "KeePass trash delete failed, reverting local trash state for totp id=${item.id}")
                        repository.updateItem(item.copy(updatedAt = Date()))
                    }
                }
            } else {
                // 永久删除
                repository.deleteItem(item)
                // 删除操作 - 记录日志
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.TOTP,
                    itemId = item.id,
                    itemTitle = item.title
                )
            }
        }
    }
    
    /**
     * 切换收藏状态
     */
    fun toggleFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.updateFavoriteStatus(id, isFavorite)
        }
    }
    
    /**
     * 更新排序顺序
     */
    fun updateSortOrders(items: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateSortOrders(items)
        }
    }
    
    /**
     * HOTP专用: 增加计数器并重新生成验证码
     * @param itemId HOTP项目ID
     */
    fun incrementHotpCounter(itemId: Long) {
        viewModelScope.launch {
            try {
                val item = repository.getItemById(itemId) ?: return@launch
                
                // 解析TOTP数据
                val totpData = parseStoredTotpData(item) ?: return@launch
                
                // 只处理HOTP类型
                if (totpData.otpType != takagi.ru.monica.data.model.OtpType.HOTP) {
                    return@launch
                }
                
                // 增加计数器
                val updatedTotpData = totpData.copy(counter = totpData.counter + 1)
                val updatedItemData = encodeStoredSensitiveValueForRewrite(
                    item.itemData,
                    Json.encodeToString(updatedTotpData)
                )
                
                // 更新数据库
                val updatedItem = item.copy(
                    itemData = updatedItemData,
                    updatedAt = Date()
                )
                
                repository.updateItem(updatedItem)
            } catch (e: Exception) {
            }
        }
    }
    
    /**
     * 移动TOTP到指定分类
     */
    fun moveToCategory(ids: List<Long>, categoryId: Long?) {
        viewModelScope.launch {
            ids.forEach { id ->
                try {
                    val item = repository.getItemById(id) ?: return@forEach
                    
                    // 更新TotpData中的categoryId
                    val totpData = parseStoredTotpData(item) ?: return@forEach
                    val updatedTotpData = totpData.copy(categoryId = categoryId)
                    val updatedItemData = encodeStoredSensitiveValueForRewrite(
                        item.itemData,
                        Json.encodeToString(updatedTotpData)
                    )
                    
                    // 更新数据库
                    val updatedItem = item.copy(
                        itemData = updatedItemData,
                        categoryId = categoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        keepassEntryUuid = null,
                        keepassGroupUuid = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null,
                        bitwardenCipherId = null,
                        bitwardenRevisionDate = null,
                        bitwardenLocalModified = false,
                        mdbxDatabaseId = null,
                        syncStatus = "NONE",
                        updatedAt = Date()
                    )
                    val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
                        existingItem = item,
                        updatedItem = updatedItem,
                        persistUpdate = { persistedItem ->
                            repository.updateItem(persistedItem)
                        }
                    )
                    if (keepassSync.isFailure && item.keepassDatabaseId != null) {
                        Log.e(
                            "TotpViewModel",
                            "KeePass TOTP source delete failed after move to category; local target was kept: ${keepassSync.exceptionOrNull()?.message}"
                        )
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    suspend fun copyTotpToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Long? {
        if (item.itemType != ItemType.TOTP || item.hasOwnershipConflict()) return null
        val totpData = parseStoredTotpData(item) ?: return null
        val detachedTotpData = totpData.copy(
            boundPasswordId = null,
            categoryId = categoryId,
            keepassDatabaseId = null
        )
        val localCopy = item.asMonicaLocalCopy(categoryId).copy(
            itemData = encodeStoredSensitiveValueForRewrite(
                item.itemData,
                Json.encodeToString(detachedTotpData)
            ),
            createdAt = Date(),
            updatedAt = Date()
        )
        return repository.insertItem(localCopy)
    }

    suspend fun moveTotpToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Result<Long> {
        if (item.itemType != ItemType.TOTP) {
            return Result.failure(IllegalArgumentException("仅支持验证器项目"))
        }
        if (item.hasOwnershipConflict()) {
            return Result.failure(IllegalStateException("验证器来源冲突，无法移动到 Monica 本地"))
        }

        val newId = copyTotpToMonicaLocal(item, categoryId)
            ?: return Result.failure(IllegalStateException("创建 Monica 本地验证器副本失败"))

        val sourceDelete = when (val ownership = item.resolveOwnership()) {
            is SecureItemOwnership.Bitwarden -> {
                val vaultId = ownership.vaultId
                val cipherId = ownership.cipherId
                if (vaultId == null || cipherId.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Bitwarden 验证器缺少同步标识"))
                } else {
                    bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_TOTP
                    ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
                }
            }
            is SecureItemOwnership.KeePass -> {
                if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("KeePass 验证器源删除失败"))
                }
            }
            is SecureItemOwnership.MonicaLocal -> Result.success(Unit)
            is SecureItemOwnership.Mdbx -> Result.success(Unit)
            is SecureItemOwnership.Conflict -> Result.failure(IllegalStateException("验证器来源冲突，无法移动到 Monica 本地"))
        }

        if (sourceDelete.isFailure) {
            Log.e(
                "TotpViewModel",
                "TOTP move to Monica local kept target copy after source cleanup failed; sourceId=${item.id} targetId=$newId error=${sourceDelete.exceptionOrNull()?.message}"
            )
            return Result.failure(
                sourceDelete.exceptionOrNull() ?: IllegalStateException("删除验证器源失败")
            )
        }

        repository.deleteItem(item)
        return Result.success(newId)
    }

    suspend fun moveTotpToStorage(
        id: Long,
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ): Boolean {
        return try {
            val item = repository.getItemById(id) ?: return false
            val totpData = parseStoredTotpData(item) ?: return false
            val targetMdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null
            val updatedData = when {
                mdbxDatabaseId != null -> totpData.copy(
                    boundPasswordId = null,
                    categoryId = null,
                    keepassDatabaseId = null
                )
                keepassDatabaseId != null -> totpData.copy(keepassDatabaseId = keepassDatabaseId)
                bitwardenVaultId != null -> totpData.copy(keepassDatabaseId = null)
                else -> totpData.copy(categoryId = categoryId)
            }
            val keepassIdentity = resolveKeePassMutationIdentity(
                existingItem = item,
                targetDatabaseId = keepassDatabaseId,
                requestedGroupPath = keepassGroupPath
            )
            val updatedItem = item.copy(
                itemData = encodeStoredSensitiveValueForRewrite(
                    item.itemData,
                    Json.encodeToString(updatedData)
                ),
                categoryId = if (mdbxDatabaseId != null) null else categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = targetMdbxFolderId,
                updatedAt = Date()
            )
            val transition = resolveBitwardenTransition(
                existingItem = item,
                targetVaultId = bitwardenVaultId,
                targetFolderId = bitwardenFolderId,
                forcePendingWhenKeepingCipher =
                    bitwardenVaultId != null &&
                        item.bitwardenVaultId == bitwardenVaultId &&
                        !item.bitwardenCipherId.isNullOrBlank(),
                abortOnQueueFailure = true
            ) ?: return false
            val finalUpdatedItem = updatedItem.copy(
                bitwardenCipherId = transition.cipherId,
                bitwardenRevisionDate = transition.revisionDate,
                bitwardenLocalModified = transition.localModified,
                syncStatus = transition.syncStatus
            )
            val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
                existingItem = item,
                updatedItem = finalUpdatedItem,
                persistUpdate = { persistedItem ->
                    repository.updateItem(persistedItem)
                }
            )
            if (keepassSync.isFailure) {
                Log.e(
                    "TotpViewModel",
                    "TOTP move failed before local update; sourceId=$id keepassTarget=$keepassDatabaseId mdbxTarget=$mdbxDatabaseId bitwardenTarget=$bitwardenVaultId error=${keepassSync.exceptionOrNull()?.message}"
                )
                return false
            }
            requestBitwardenMutationSync(bitwardenVaultId)
            true
        } catch (e: Exception) {
            Log.e("TotpViewModel", "TOTP move failed id=$id", e)
            false
        }
    }

    fun moveToKeePassDatabase(ids: List<Long>, databaseId: Long?) {
        viewModelScope.launch {
            ids.forEach { id ->
                moveTotpToStorage(id = id, keepassDatabaseId = databaseId)
            }
        }
    }

    fun moveToKeePassGroup(ids: List<Long>, databaseId: Long, groupPath: String) {
        viewModelScope.launch {
            ids.forEach { id ->
                moveTotpToStorage(
                    id = id,
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = groupPath
                )
            }
        }
    }

    fun moveToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) {
        viewModelScope.launch {
            ids.forEach { id ->
                moveTotpToStorage(
                    id = id,
                    bitwardenVaultId = vaultId,
                    bitwardenFolderId = folderId
                )
            }
        }
    }

    fun moveToMdbxDatabase(ids: List<Long>, databaseId: Long, folderId: String? = null) {
        viewModelScope.launch {
            ids.forEach { id ->
                moveTotpToStorage(
                    id = id,
                    mdbxDatabaseId = databaseId,
                    mdbxFolderId = folderId
                )
            }
        }
    }

    private suspend fun resolveBitwardenTransition(
        existingItem: SecureItem?,
        targetVaultId: Long?,
        targetFolderId: String?,
        forcePendingWhenKeepingCipher: Boolean,
        abortOnQueueFailure: Boolean
    ) = SecureItemBitwardenTransitionResolver.resolve(
        tag = "TotpViewModel",
        existingItem = existingItem,
        targetVaultId = targetVaultId,
        targetFolderId = targetFolderId,
        forcePendingWhenKeepingCipher = forcePendingWhenKeepingCipher,
        abortOnQueueFailure = abortOnQueueFailure
    ) { vaultId, cipherId, entryId ->
        bitwardenRepository?.queueCipherDelete(
            vaultId = vaultId,
            cipherId = cipherId,
            entryId = entryId,
            itemType = BitwardenPendingOperation.ITEM_TYPE_TOTP
        )
    }
    
    /**
     * 添加新分类
     */
    fun addCategory(name: String, onResult: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val category = Category(name = name)
            val id = passwordRepository.insertCategory(category)
            onResult(id)
        }
    }
    
    /**
     * 更新分类
     */
    fun updateCategory(category: Category) {
        viewModelScope.launch {
            passwordRepository.updateCategory(category)
        }
    }
    
    /**
     * 删除分类
     */
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            passwordRepository.deleteCategory(category)
            // 如果当前过滤器是这个分类，重置为全部
            if (_categoryFilter.value is TotpCategoryFilter.Custom &&
                (_categoryFilter.value as TotpCategoryFilter.Custom).categoryId == category.id) {
                _categoryFilter.value = TotpCategoryFilter.All
            }
        }
    }
}

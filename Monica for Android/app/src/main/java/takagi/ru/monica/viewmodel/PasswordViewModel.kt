package takagi.ru.monica.viewmodel

import takagi.ru.monica.utils.StringResolver

import takagi.ru.monica.R

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import takagi.ru.monica.bitwarden.BitwardenMutationStateHelper
import takagi.ru.monica.bitwarden.cache.BitwardenOfflineSecretCache
import takagi.ru.monica.bitwarden.service.BitwardenSyncSnapshotPreviewParser
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.domain.provider.BitwardenPasswordProvider
import takagi.ru.monica.domain.provider.DefaultPasswordProvider
import takagi.ru.monica.domain.provider.KeePassPasswordProvider
import takagi.ru.monica.domain.provider.PasswordCommandStateFactory
import takagi.ru.monica.domain.provider.PasswordProviderRegistry
import takagi.ru.monica.domain.provider.PasswordSource
import takagi.ru.monica.domain.provider.MdbxPasswordProvider
import takagi.ru.monica.keepass.KeePassPasswordCreateExecutor
import takagi.ru.monica.keepass.KeePassCrossDatabaseTransfer
import takagi.ru.monica.keepass.KeePassPasswordUpdateExecutor
import takagi.ru.monica.keepass.KeePassPasswordDeleteExecutor
import takagi.ru.monica.keepass.KeePassTotpProjectionMatcher
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.resolvedActiveStorageLocation
import takagi.ru.monica.data.PasswordOwnership
import takagi.ru.monica.data.PasswordArchiveSyncMeta
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.PasswordHistoryManager
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.data.writeOperationAvailability
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.steam.data.SteamExternalMaFileContract
import takagi.ru.monica.repository.asMdbxBatchCopy
import takagi.ru.monica.repository.asMdbxBatchMove
import takagi.ru.monica.repository.findMdbxReplicaTargetConflictIds
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.rustcore.RustPasswordListCore
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassUriPermissionState
import takagi.ru.monica.utils.keePassUriPermissionState
import takagi.ru.monica.utils.KeePassSecureItemData
import takagi.ru.monica.utils.buildKeePassPathKey
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.applyToPasswordEntry
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.ui.model.SecretValueState
import takagi.ru.monica.ui.model.plainValueOrEmpty
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncItemKind
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.util.TotpDataResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Date
import java.net.URI
import java.util.Locale
import java.util.UUID

import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.data.bitwarden.BitwardenFolder

sealed class CategoryFilter {
    object All : CategoryFilter()
    object Archived : CategoryFilter()
    object Local : CategoryFilter() // Pure local view (Monica)
    object LocalOnly : CategoryFilter() // Local entries that have no matching item in Bitwarden
    object Starred : CategoryFilter()
    object Uncategorized : CategoryFilter()
    object LocalStarred : CategoryFilter()
    object LocalUncategorized : CategoryFilter()
    data class Custom(val categoryId: Long) : CategoryFilter()
    data class KeePassDatabase(val databaseId: Long) : CategoryFilter()
    data class KeePassGroupFilter(
        val databaseId: Long,
        val groupPath: String,
        val groupUuid: String? = null
    ) : CategoryFilter()
    data class KeePassDatabaseStarred(val databaseId: Long) : CategoryFilter()
    data class KeePassDatabaseUncategorized(val databaseId: Long) : CategoryFilter()
    data class MdbxDatabase(val databaseId: Long) : CategoryFilter()
    data class MdbxFolderFilter(val databaseId: Long, val folderId: String) : CategoryFilter()
    data class BitwardenVault(val vaultId: Long) : CategoryFilter()
    data class BitwardenFolderFilter(val folderId: String, val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultStarred(val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultUncategorized(val vaultId: Long) : CategoryFilter()
}

internal class PasswordArchiveFilterController {
    private var returnFilter: CategoryFilter? = null

    fun open(currentFilter: CategoryFilter): CategoryFilter {
        if (currentFilter !is CategoryFilter.Archived) {
            returnFilter = currentFilter
        }
        return CategoryFilter.Archived
    }

    fun close(): CategoryFilter {
        val target = returnFilter ?: CategoryFilter.All
        returnFilter = null
        return target
    }

    fun clear() {
        returnFilter = null
    }
}

sealed class BitwardenRecoveryResult {
    object Success : BitwardenRecoveryResult()
    data class Error(val message: String) : BitwardenRecoveryResult()
    data class EmptyVaultBlocked(val reason: String) : BitwardenRecoveryResult()
}

data class BitwardenSyncRawHistoryItem(
    val id: Long,
    val operation: String,
    val endpoint: String,
    val payloadSource: String,
    val payloadDigest: String,
    val responseCode: Int?,
    val success: Boolean,
    val capturedAt: Long,
    val payload: String?,
    val preview: BitwardenSyncSnapshotPreview? = null
)

internal data class MdbxSecureItemBatchResult(
    val successCount: Int,
    val failedCount: Int,
    val createdIds: List<Long> = emptyList()
)

private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"

data class PasswordCredentialDraft(
    val username: String = "",
    val password: String = "",
    val authenticatorKey: String = "",
    val customIconValue: String? = null,
    val customIconUpdatedAt: Long? = null,
    val notes: String = "",
    val boundNoteId: Long? = null,
    val email: String = "",
    val phone: String = "",
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val creditCardNumber: String = "",
    val creditCardHolder: String = "",
    val creditCardExpiry: String = "",
    val creditCardCVV: String = "",
    val passkeyBindings: String = "",
    val sshKeyData: String = "",
    val customFields: List<CustomFieldDraft> = emptyList()
)

data class KeePassPermissionRepairRequest(
    val databaseId: Long,
    val entry: PasswordEntry
)

data class SavedPasswordCredential(
    val credentialIndex: Int,
    val firstPasswordId: Long,
    val savedPasswordIds: List<Long>,
    val replicaGroupId: String
)

data class EditedPasswordCredentialSavePlan(
    val existingCredential: PasswordCredentialDraft,
    val newCredentials: List<PasswordCredentialDraft>
)

internal fun buildEditedPasswordCredentialSavePlan(
    originalIds: List<Long>,
    credentials: List<PasswordCredentialDraft>
): EditedPasswordCredentialSavePlan? {
    if (originalIds.size != 1 || credentials.size <= 1) return null
    return EditedPasswordCredentialSavePlan(
        existingCredential = credentials.first(),
        newCredentials = credentials.drop(1)
    )
}

internal fun mergePasswordCredentialCustomFields(
    commonFields: List<CustomFieldDraft>,
    credentialFields: List<CustomFieldDraft>
): List<CustomFieldDraft> {
    val credentialTitles = credentialFields
        .mapNotNull { field ->
            field.title.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        }
        .toSet()
    return commonFields.filterNot { field ->
        field.title.trim().lowercase(Locale.ROOT) in credentialTitles
    } + credentialFields
}

internal fun buildIndependentPasswordCredentialTemplates(
    commonEntry: PasswordEntry,
    credentials: List<PasswordCredentialDraft>,
    replicaGroupIdFactory: () -> String = { "password:${UUID.randomUUID()}" }
): List<PasswordEntry> = credentials.map { credential ->
    commonEntry.copy(
        id = 0,
        username = credential.username.trim(),
        password = credential.password.trim(),
        authenticatorKey = credential.authenticatorKey,
        customIconValue = credential.customIconValue ?: commonEntry.customIconValue,
        customIconUpdatedAt = credential.customIconUpdatedAt ?: commonEntry.customIconUpdatedAt,
        notes = credential.notes,
        boundNoteId = credential.boundNoteId,
        email = credential.email,
        phone = credential.phone,
        addressLine = credential.addressLine,
        city = credential.city,
        state = credential.state,
        zipCode = credential.zipCode,
        country = credential.country,
        creditCardNumber = credential.creditCardNumber,
        creditCardHolder = credential.creditCardHolder,
        creditCardExpiry = credential.creditCardExpiry,
        creditCardCVV = credential.creditCardCVV,
        passkeyBindings = credential.passkeyBindings,
        sshKeyData = credential.sshKeyData,
        keepassEntryUuid = null,
        keepassGroupUuid = null,
        bitwardenCipherId = null,
        bitwardenRevisionDate = null,
        bitwardenLocalModified = false,
        replicaGroupId = replicaGroupIdFactory()
    )
}

private data class KeePassCustomFieldFingerprint(
    val title: String,
    val value: String,
    val isProtected: Boolean,
    val sortOrder: Int
)

private data class PasswordEntriesFilterRequest(
    val query: String,
    val filter: CategoryFilter,
)
private const val PASSWORD_SCROLL_DEBUG_LOGS_ENABLED = false

/**
 * ViewModel for password management
 */
class PasswordViewModel internal constructor(
    private val repository: PasswordRepository,
    private val securityManager: SecurityManager,
    private val secureItemRepository: SecureItemRepository? = null,
    private val customFieldRepository: CustomFieldRepository? = null,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    private val strings: StringResolver
) : ViewModel() {
    private val decryptLock = Any()
    private val appContext: Context? = context?.applicationContext
    private val _keepassPermissionRepairRequests = MutableSharedFlow<KeePassPermissionRepairRequest>(extraBufferCapacity = 1)
    val keepassPermissionRepairRequests: SharedFlow<KeePassPermissionRepairRequest> =
        _keepassPermissionRepairRequests.asSharedFlow()

    private fun showKeePassWritePermissionError() {
        appContext?.let { app ->
            Toast.makeText(
                app,
                app.getString(takagi.ru.monica.R.string.keepass_write_permission_delete_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private val bitwardenSnapshotPreviewParser = BitwardenSyncSnapshotPreviewParser()

    companion object {
        private const val SAVED_FILTER_ALL = "all"
        private const val SAVED_FILTER_ARCHIVED = "archived"
        private const val SAVED_FILTER_LOCAL = "local"
        private const val SAVED_FILTER_LOCAL_ONLY = "local_only"
        private const val SAVED_FILTER_STARRED = "starred"
        private const val SAVED_FILTER_UNCATEGORIZED = "uncategorized"
        private const val SAVED_FILTER_LOCAL_STARRED = "local_starred"
        private const val SAVED_FILTER_LOCAL_UNCATEGORIZED = "local_uncategorized"
        private const val SAVED_FILTER_CUSTOM = "custom"
        private const val SAVED_FILTER_KEEPASS_DATABASE = "keepass_database"
        private const val SAVED_FILTER_KEEPASS_GROUP = "keepass_group"
        private const val SAVED_FILTER_KEEPASS_DATABASE_STARRED = "keepass_database_starred"
        private const val SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED = "keepass_database_uncategorized"
        private const val SAVED_FILTER_BITWARDEN_VAULT = "bitwarden_vault"
        private const val SAVED_FILTER_BITWARDEN_FOLDER = "bitwarden_folder"
        private const val SAVED_FILTER_BITWARDEN_VAULT_STARRED = "bitwarden_vault_starred"
        private const val SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED = "bitwarden_vault_uncategorized"
        private const val SAVED_FILTER_MDBX_DATABASE = "mdbx_database"
        private const val SAVED_FILTER_MDBX_FOLDER = "mdbx_folder"
        private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__monica_manual_stack_group"
        private const val MONICA_NO_STACK_FIELD_TITLE = "__monica_no_stack"
        private const val MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME = ".Monica"
        private const val MONICA_KEEPASS_ARCHIVE_GROUP_NAME = "Archive"
        private const val PASSWORD_HISTORY_LIMIT = 10
        private const val KEEPASS_BATCH_DELETE_CHUNK_SIZE = 40
    }

    enum class ManualStackMode {
        STACK,
        AUTO_STACK,
        NEVER_STACK
    }
    
    private val passwordHistoryManager: PasswordHistoryManager? = context?.let { PasswordHistoryManager(it) }
    private val settingsManager: takagi.ru.monica.utils.SettingsManager? = context?.let { takagi.ru.monica.utils.SettingsManager(it) }
    private val bitwardenRepository: BitwardenRepository? = context?.let { BitwardenRepository.getInstance(it.applicationContext) }
    private val bitwardenOfflineSecretCache: BitwardenOfflineSecretCache? = context?.applicationContext?.let {
        BitwardenOfflineSecretCache(it, securityManager)
    }
    private val keepassBridge = if (context != null && localKeePassDatabaseDao != null) {
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
    private val keepassPasswordDeleteExecutor = KeePassPasswordDeleteExecutor(keepassBridge)
    private val keepassPasswordCreateExecutor = KeePassPasswordCreateExecutor(keepassBridge)
    private val keepassPasswordUpdateExecutor = KeePassPasswordUpdateExecutor(keepassBridge)
    private val defaultPasswordProvider = DefaultPasswordProvider(
        decodePassword = ::decodePasswordOrNull,
        encryptPassword = securityManager::encryptData
    )
    private val passwordCommandStateFactory = PasswordCommandStateFactory()
    private val passwordProviderRegistry = PasswordProviderRegistry(
        providers = listOf(
            KeePassPasswordProvider(
                decodePassword = ::decodePasswordOrNull,
                encryptPassword = securityManager::encryptData
            ),
            MdbxPasswordProvider(
                decodePassword = ::decodePasswordOrNull,
                encryptPassword = securityManager::encryptData
            ),
            BitwardenPasswordProvider(
                decodePassword = ::decodePasswordOrNull,
                encryptPassword = securityManager::encryptData,
                loadOfflineCachedSecret = ::loadBitwardenOfflineCachedSecret,
                rememberOfflineCachedSecret = ::rememberBitwardenOfflineCachedSecret
            )
        ),
        fallbackProvider = defaultPasswordProvider
    )

    private fun decryptStoredSensitiveValue(value: String): String {
        return runCatching {
            securityManager.decryptDataIfMonicaCiphertext(value)
        }.getOrDefault(value)
    }

    private fun looksLikeStoredSensitiveCiphertext(value: String): Boolean {
        return securityManager.looksLikeMonicaCiphertext(value)
    }

    private fun encodeStoredSensitiveValueForCopy(originalValue: String?, plainValue: String): String {
        return if (!originalValue.isNullOrBlank() && looksLikeStoredSensitiveCiphertext(originalValue)) {
            securityManager.encryptDataLegacyCompat(plainValue)
        } else {
            plainValue
        }
    }

    private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String {
        if (plainValue.isBlank()) return plainValue
        return securityManager.encryptDataLegacyCompat(plainValue)
    }

    private fun encodeAuthenticatorKeyForStorage(value: String): String {
        if (value.isBlank()) return ""
        val plainValue = decryptStoredSensitiveValue(value)
        return encodeStoredSensitiveValueForNewWrite(plainValue)
    }

    private fun parseStoredTotpData(item: SecureItem): TotpData? {
        return TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        )
    }

    private fun parseStoredAuthenticatorKey(
        password: PasswordEntry,
        fallbackIssuer: String = password.website.takeIf { it.isNotBlank() } ?: password.title,
        fallbackAccountName: String = password.username.takeIf { it.isNotBlank() } ?: password.title
    ): TotpData? {
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptStoredSensitiveValue(password.authenticatorKey),
            fallbackIssuer = fallbackIssuer,
            fallbackAccountName = fallbackAccountName
        )
    }
    
    // Trash settings
    private val trashSettings = settingsManager?.settingsFlow?.map { 
        it.trashEnabled to it.trashAutoDeleteDays 
    }?.stateIn(viewModelScope, SharingStarted.Eagerly, true to 30)

    // Smart Deduplication setting
    private val smartDeduplicationEnabled = settingsManager?.settingsFlow?.map { 
        it.smartDeduplicationEnabled 
    }?.stateIn(viewModelScope, SharingStarted.Eagerly, true) ?: kotlinx.coroutines.flow.MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _categoryFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)
    val categoryFilter = _categoryFilter.asStateFlow()
    private val archiveFilterController = PasswordArchiveFilterController()

    private val _mdbxFoldersByDatabase = MutableStateFlow<Map<Long, List<MdbxStoredFolderEntry>>>(emptyMap())
    val mdbxFoldersByDatabase: StateFlow<Map<Long, List<MdbxStoredFolderEntry>>> =
        _mdbxFoldersByDatabase.asStateFlow()

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    fun toggleExpandedGroup(groupKey: String) {
        _expandedGroups.value = if (_expandedGroups.value.contains(groupKey)) {
            _expandedGroups.value - groupKey
        } else {
            _expandedGroups.value + groupKey
        }
    }

    fun clearExpandedGroups() {
        _expandedGroups.value = emptySet()
    }

    private val _fastScrollRequestKey = MutableStateFlow(0)
    val fastScrollRequestKey: StateFlow<Int> = _fastScrollRequestKey.asStateFlow()
    private val _fastScrollProgress = MutableStateFlow(0f)
    val fastScrollProgress: StateFlow<Float> = _fastScrollProgress.asStateFlow()
    private val _passwordListScrollIndex = MutableStateFlow(0)
    val passwordListScrollIndex: StateFlow<Int> = _passwordListScrollIndex.asStateFlow()
    private val _passwordListScrollOffset = MutableStateFlow(0)
    val passwordListScrollOffset: StateFlow<Int> = _passwordListScrollOffset.asStateFlow()
    private val _passwordListScrollAnchorKey = MutableStateFlow<String?>(null)
    val passwordListScrollAnchorKey: StateFlow<String?> = _passwordListScrollAnchorKey.asStateFlow()

    private val categoriesSource = repository.getAllCategories()
        .distinctUntilChanged()
    val categoriesReady: StateFlow<Boolean> = categoriesSource
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val categories = categoriesSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()
    private var hasLoggedDecryptAuthStateWarning = false

    init {
        restoreLastCategoryFilter()
        observeInvalidCustomCategoryFilter()
    }
    
    fun getBitwardenFolders(vaultId: Long): Flow<List<BitwardenFolder>> {
        return repository.getBitwardenFoldersByVaultId(vaultId)
    }

    fun getMdbxFolders(databaseId: Long): Flow<List<MdbxStoredFolderEntry>> {
        return mdbxFoldersByDatabase.map { foldersByDatabase ->
            foldersByDatabase[databaseId].orEmpty()
        }.distinctUntilChanged()
    }

    fun refreshMdbxFolders(databaseId: Long) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.listMdbxFolders(databaseId)
                }
            }.onSuccess { folders ->
                _mdbxFoldersByDatabase.value = _mdbxFoldersByDatabase.value + (databaseId to folders)
            }.onFailure { error ->
                Log.w("PasswordViewModel", "Failed to refresh MDBX folders for database $databaseId", error)
            }
        }
    }

    fun requestFastScroll(progress: Float) {
        val safeProgress = progress.coerceIn(0f, 1f)
        val nextRequestKey = _fastScrollRequestKey.value + 1
        _fastScrollProgress.value = safeProgress
        _fastScrollRequestKey.value = nextRequestKey
        if (PASSWORD_SCROLL_DEBUG_LOGS_ENABLED) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_request_fast_scroll progress=$safeProgress requestKey=$nextRequestKey"
            )
        }
    }

    fun updateFastScrollProgress(progress: Float) {
        _fastScrollProgress.value = progress.coerceIn(0f, 1f)
    }

    fun updatePasswordListScrollPosition(
        index: Int,
        offset: Int,
        anchorKey: String? = null,
        source: String = "unknown"
    ) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        val previousIndex = _passwordListScrollIndex.value
        val previousOffset = _passwordListScrollOffset.value
        val previousAnchorKey = _passwordListScrollAnchorKey.value
        val indexChanged = previousIndex != safeIndex
        val offsetChanged = previousOffset != safeOffset
        val anchorChanged = previousAnchorKey != anchorKey
        if (indexChanged) {
            _passwordListScrollIndex.value = safeIndex
        }
        if (offsetChanged) {
            _passwordListScrollOffset.value = safeOffset
        }
        if (anchorChanged) {
            _passwordListScrollAnchorKey.value = anchorKey
        }
        if (PASSWORD_SCROLL_DEBUG_LOGS_ENABLED && (indexChanged || offsetChanged || anchorChanged)) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=$source old=$previousIndex/$previousOffset anchor=$previousAnchorKey new=$safeIndex/$safeOffset anchor=$anchorKey"
            )
        }
    }

    private val debouncedSearchQuery: Flow<String> = searchQuery
        .debounce(300)
        .distinctUntilChanged()

    private val passwordFlowSharingStarted = SharingStarted.WhileSubscribed(5000)

    // Room invalidation trackers are relatively expensive on large vaults.
    // Share the two raw tables across filtered, decrypted and metadata-only consumers.
    private val rawAllPasswordsSource: SharedFlow<List<PasswordEntry>> =
        repository.getAllPasswordEntries().shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )

    private val rawArchivedPasswordsSource: SharedFlow<List<PasswordEntry>> =
        repository.getArchivedEntries().shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )

    private val passwordEntriesSource: Flow<List<PasswordEntry>> = combine(
        debouncedSearchQuery,
        _categoryFilter,
    ) { query, filter ->
        PasswordEntriesFilterRequest(
            query = query,
            filter = filter,
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { request ->
            val query = request.query
            val filter = request.filter
            val localCategoryIdsInScope =
                (filter as? CategoryFilter.Custom)?.categoryId?.let(::setOf).orEmpty()
            val baseFlow: Flow<List<PasswordEntry>> = if (query.isNotBlank()) {
                // Extended search: query + custom fields, then apply current category filter in-memory.
                val searchFlow = rawAllPasswordsSource.map { allEntries ->
                    val baseResults = RustPasswordListCore.filterEntries(allEntries, query)
                        ?: allEntries.filter { matchesSearchQuery(it, query) }
                    val customFieldMatchIds = try {
                        customFieldRepository?.searchEntryIdsByFieldContent(query) ?: emptyList()
                    } catch (e: Exception) {
                        Log.w("PasswordViewModel", "Custom field search failed", e)
                        emptyList()
                    }
                    
                    if (customFieldMatchIds.isEmpty()) {
                        baseResults
                    } else {
                        val baseIds = baseResults.map { it.id }.toSet()
                        val additionalIds = customFieldMatchIds.filter { it !in baseIds }
                        
                        if (additionalIds.isEmpty()) {
                            baseResults
                        } else {
                            val additionalEntries = try {
                                repository.getActivePasswordsByIds(additionalIds)
                            } catch (e: Exception) {
                                Log.w("PasswordViewModel", "Failed to fetch custom field matched entries", e)
                                emptyList()
                            }
                            (baseResults + additionalEntries).distinctBy { it.id }
                        }
                    }
                }

                when (filter) {
                    is CategoryFilter.Archived -> rawArchivedPasswordsSource.map { archivedEntries ->
                        val byText = archivedEntries.filter { matchesSearchQuery(it, query) }
                        val customFieldMatchIds = try {
                            customFieldRepository?.searchEntryIdsByFieldContent(query)?.toSet() ?: emptySet()
                        } catch (e: Exception) {
                            Log.w("PasswordViewModel", "Custom field search failed in archived view", e)
                            emptySet()
                        }
                        if (customFieldMatchIds.isEmpty()) {
                            byText
                        } else {
                            val existingIds = byText.map { it.id }.toHashSet()
                            byText + archivedEntries.filter { it.id in customFieldMatchIds && it.id !in existingIds }
                        }
                    }
                    is CategoryFilter.LocalOnly -> combine(
                        searchFlow,
                        rawAllPasswordsSource
                    ) { searchResults, allEntries ->
                        val localOnlyIds = filterLocalOnlyComparedToBitwarden(allEntries)
                            .asSequence()
                            .map { it.id }
                            .toHashSet()
                        searchResults.filter { it.id in localOnlyIds }
                    }
                    else -> searchFlow.map { searchResults ->
                        applyCategoryFilterInMemory(
                            entries = searchResults,
                            filter = filter,
                            localCategoryIdsInScope = localCategoryIdsInScope,
                        )
                    }
                }
            } else {

                when (filter) {
                    is CategoryFilter.All -> rawAllPasswordsSource
                    is CategoryFilter.Archived -> rawArchivedPasswordsSource
                    is CategoryFilter.Local -> rawAllPasswordsSource.map { list ->
                        list.filter { it.isLocalOnlyEntry() }
                    }
                    is CategoryFilter.LocalOnly -> rawAllPasswordsSource.map { list ->
                        filterLocalOnlyComparedToBitwarden(list)
                    }
                    is CategoryFilter.Starred -> repository.getFavoritePasswordEntries()
                    is CategoryFilter.Uncategorized -> repository.getUncategorizedPasswordEntries()
                    is CategoryFilter.LocalStarred -> rawAllPasswordsSource.map { list ->
                        list.filter { it.isLocalOnlyEntry() && it.isFavorite }
                    }
                    is CategoryFilter.LocalUncategorized -> rawAllPasswordsSource.map { list ->
                        list.filter { it.isLocalOnlyEntry() && it.categoryId == null }
                    }
                    is CategoryFilter.Custom -> rawAllPasswordsSource.map { list ->
                        val categoryIds = localCategoryIdsInScope.ifEmpty { setOf(filter.categoryId) }
                        list.filter { entry ->
                            entry.isLocalOnlyEntry() && entry.categoryId in categoryIds
                        }
                    }
                    is CategoryFilter.KeePassDatabase -> repository.getPasswordEntriesByKeePassDatabase(filter.databaseId)
                    is CategoryFilter.KeePassGroupFilter -> repository.getPasswordEntriesByKeePassGroup(
                        filter.databaseId,
                        filter.groupPath,
                        filter.groupUuid
                    )
                    is CategoryFilter.KeePassDatabaseStarred -> rawAllPasswordsSource.map { list ->
                        list.filter { it.keepassDatabaseId == filter.databaseId && it.isFavorite }
                    }
                    is CategoryFilter.KeePassDatabaseUncategorized -> rawAllPasswordsSource.map { list ->
                        list.filter { it.keepassDatabaseId == filter.databaseId && it.keepassGroupPath.isNullOrBlank() }
                    }
                    is CategoryFilter.BitwardenVault -> repository.getPasswordEntriesByBitwardenVault(filter.vaultId)
                    is CategoryFilter.BitwardenFolderFilter -> repository.getPasswordEntriesByBitwardenFolder(
                        filter.vaultId,
                        filter.folderId
                    )
                    is CategoryFilter.BitwardenVaultStarred -> rawAllPasswordsSource.map { list ->
                        list.filter { it.bitwardenVaultId == filter.vaultId && it.isFavorite }
                    }
                    is CategoryFilter.BitwardenVaultUncategorized -> rawAllPasswordsSource.map { list ->
                        list.filter { it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == null }
                    }
                    is CategoryFilter.MdbxDatabase -> rawAllPasswordsSource.map { list ->
                        list.filter { it.mdbxDatabaseId == filter.databaseId }
                    }
                    is CategoryFilter.MdbxFolderFilter -> rawAllPasswordsSource.map { list ->
                        list.filter { it.matchesMdbxFolder(filter.databaseId, filter.folderId) }
                    }
                }
            }
            // Combine with settings for smart deduplication logic
            combine(baseFlow, smartDeduplicationEnabled) { entries, smartDedupe ->
                // Dedupe logic:
                // 1. If searching, or explicit Local/KeePass/Bitwarden filter -> NO dedupe (show raw data).
                // 2. If "All" or other categories -> Apply Smart Dedupe if enabled.
                val isExplicitSourceView = when (filter) {
                    is CategoryFilter.BitwardenVault -> true
                    is CategoryFilter.BitwardenFolderFilter -> true // Explicit folder view
                    is CategoryFilter.KeePassDatabase -> true
                    is CategoryFilter.KeePassGroupFilter -> true
                    is CategoryFilter.KeePassDatabaseStarred -> true
                    is CategoryFilter.KeePassDatabaseUncategorized -> true
                    is CategoryFilter.Local -> true // Local view shows all local entries
                    is CategoryFilter.LocalOnly -> true
                    is CategoryFilter.LocalStarred -> true
                    is CategoryFilter.LocalUncategorized -> true
                    is CategoryFilter.BitwardenVaultStarred -> true
                    is CategoryFilter.BitwardenVaultUncategorized -> true
                    is CategoryFilter.MdbxDatabase -> true
                    is CategoryFilter.MdbxFolderFilter -> true
                    else -> false
                }
                
                // Smart dedupe is only for non-search "All" view and does not mutate source data.
                val shouldDedupe = query.isBlank() && !isExplicitSourceView && smartDedupe && filter is CategoryFilter.All
                val shouldKeepRawDisplay = query.isNotBlank() || isExplicitSourceView
                
                val filtered = if (shouldDedupe) {
                    dedupeSmart(entries)
                } else {
                    entries
                }
                val exactDeduped = if (shouldKeepRawDisplay) {
                    filtered
                } else {
                    dedupeExactEntries(filtered)
                }
                // Keep the stored ciphertext intact for explicit copy/move operations, but
                // do not decrypt every row merely to render the password list.
                if (shouldKeepRawDisplay) {
                    exactDeduped
                } else {
                    filterGhostEntriesForDisplay(exactDeduped)
                }
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    private val sharedPasswordEntriesSource: SharedFlow<List<PasswordEntry>> =
        passwordEntriesSource.shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )
    val passwordEntriesReady: StateFlow<Boolean> = sharedPasswordEntriesSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val passwordEntries: StateFlow<List<PasswordEntry>> = sharedPasswordEntriesSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val allPasswordsSource: Flow<List<PasswordEntry>> = rawAllPasswordsSource
        .map { entries ->
            entries.map { entry ->
                entry.copy(password = inspectSecretState(entry).plainValueOrEmpty())
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    private val sharedAllPasswordsSource: SharedFlow<List<PasswordEntry>> =
        allPasswordsSource.shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )
    val allPasswordsReady: StateFlow<Boolean> = sharedAllPasswordsSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val allPasswords: StateFlow<List<PasswordEntry>> = sharedAllPasswordsSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Lightweight stream for list metadata/lookup use-cases.
    // Keep password blank to avoid redundant decrypt work and avoid exposing ciphertext to UI consumers.
    private val allPasswordsForUiSource: Flow<List<PasswordEntry>> = rawAllPasswordsSource
        .map { entries ->
            entries.map { entry ->
                if (entry.password.isEmpty()) entry else entry.copy(password = "")
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    private val sharedAllPasswordsForUiSource: SharedFlow<List<PasswordEntry>> =
        allPasswordsForUiSource.shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )
    val allPasswordsForUiReady: StateFlow<Boolean> = sharedAllPasswordsForUiSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val allPasswordsForUi: StateFlow<List<PasswordEntry>> = sharedAllPasswordsForUiSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Startup repair and offline-secret warming are important, but neither is required
    // to produce the first password-list frame. Defer them until the primary list
    // metadata and categories have emitted once, then give the UI a short settling
    // window before starting CPU/I/O-heavy maintenance.
    init {
        viewModelScope.launch {
            combine(
                passwordEntriesReady,
                allPasswordsForUiReady,
                categoriesReady,
            ) { entriesReady, lookupReady, categoryListReady ->
                entriesReady && lookupReady && categoryListReady
            }
                .filter { it }
                .first()

            kotlinx.coroutines.delay(1500L)

            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    repairLegacyDetachedKeePassEntries()
                    repairLegacyOwnershipConflicts()
                }.onFailure { error ->
                    Log.w("PasswordViewModel", "Deferred password maintenance failed", error)
                }
            }
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { warmupBitwardenOfflineSecretCache() }
                    .onFailure { error ->
                        Log.w("PasswordViewModel", "Deferred Bitwarden cache warmup failed", error)
                    }
            }
        }
    }

    // Lightweight archive stream for Vault V2. Password contents stay out of list state.
    private val archivedPasswordsForUiSource: Flow<List<PasswordEntry>> = rawArchivedPasswordsSource
        .map { entries ->
            entries.map { entry ->
                if (entry.password.isEmpty()) entry else entry.copy(password = "")
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    private val sharedArchivedPasswordsForUiSource: SharedFlow<List<PasswordEntry>> =
        archivedPasswordsForUiSource.shareIn(
            scope = viewModelScope,
            started = passwordFlowSharingStarted,
            replay = 1,
        )
    val archivedPasswordsForUiReady: StateFlow<Boolean> = sharedArchivedPasswordsForUiSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val archivedPasswordsForUi: StateFlow<List<PasswordEntry>> = sharedArchivedPasswordsForUiSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val archivedPasswords: StateFlow<List<PasswordEntry>> = rawArchivedPasswordsSource
        .map { entries ->
            entries.map { entry ->
                entry.copy(password = inspectSecretState(entry).plainValueOrEmpty())
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Smart display deduplication for the "All" view.
     *
     * Only rows with an explicit replica/source identity are collapsed. A
     * normal local row is an independent record, so identical title/account/
     * website/password values must remain visible as separate items.
     */
    private fun dedupeSmart(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.size <= 1) return entries

        val indexById = entries.mapIndexed { index, entry -> entry.id to index }.toMap()
        val accountGroups = entries.groupBy { buildDedupeKey(it) }
        val deduped = mutableListOf<PasswordEntry>()

        for ((_, groupEntries) in accountGroups) {
            if (groupEntries.size <= 1) {
                deduped.addAll(groupEntries)
                continue
            }

            val decrypted = groupEntries.map { entry ->
                entry to runCatching { securityManager.decryptData(entry.password) }.getOrNull()
            }

            // Only collapse rows that carry an explicit source identity. A
            // normal local row has no such identity, and two local rows may be
            // intentionally identical; keeping one here made a second entry
            // look as if it had overwritten the first one.
            deduped += dedupePasswordDisplayRows(decrypted, ::pickBestEntry)
        }

        return deduped.sortedBy { indexById[it.id] ?: Int.MAX_VALUE }
    }

    private fun applyCategoryFilterInMemory(
        entries: List<PasswordEntry>,
        filter: CategoryFilter,
        localCategoryIdsInScope: Set<Long> = emptySet(),
    ): List<PasswordEntry> {
        return when (filter) {
            is CategoryFilter.All -> entries
            is CategoryFilter.Archived -> entries
            is CategoryFilter.Local -> entries.filter { it.isLocalOnlyEntry() }
            is CategoryFilter.LocalOnly -> entries // handled separately because it needs full dataset comparison
            is CategoryFilter.Starred -> entries.filter { it.isFavorite }
            is CategoryFilter.Uncategorized -> entries.filter { it.categoryId == null }
            is CategoryFilter.LocalStarred -> entries.filter { it.isLocalOnlyEntry() && it.isFavorite }
            is CategoryFilter.LocalUncategorized -> entries.filter { it.isLocalOnlyEntry() && it.categoryId == null }
            is CategoryFilter.Custom -> {
                val categoryIds = localCategoryIdsInScope.ifEmpty { setOf(filter.categoryId) }
                entries.filter { it.categoryId in categoryIds && it.isLocalOnlyEntry() }
            }
            is CategoryFilter.KeePassDatabase -> entries.filter { it.keepassDatabaseId == filter.databaseId }
            is CategoryFilter.KeePassGroupFilter -> entries.filter {
                takagi.ru.monica.ui.KeePassGroupFilterIdentity(
                    databaseId = filter.databaseId,
                    groupPath = filter.groupPath,
                    groupUuid = filter.groupUuid
                ).matches(
                    itemDatabaseId = it.keepassDatabaseId,
                    itemGroupPath = it.keepassGroupPath,
                    itemGroupUuid = it.keepassGroupUuid
                )
            }
            is CategoryFilter.KeePassDatabaseStarred -> entries.filter {
                it.keepassDatabaseId == filter.databaseId && it.isFavorite
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> entries.filter {
                it.keepassDatabaseId == filter.databaseId && it.keepassGroupPath.isNullOrBlank()
            }
            is CategoryFilter.BitwardenVault -> entries.filter { it.bitwardenVaultId == filter.vaultId }
            is CategoryFilter.BitwardenFolderFilter -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == filter.folderId
            }
            is CategoryFilter.BitwardenVaultStarred -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.isFavorite
            }
            is CategoryFilter.BitwardenVaultUncategorized -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == null
            }
            is CategoryFilter.MdbxDatabase -> entries.filter { it.mdbxDatabaseId == filter.databaseId }
            is CategoryFilter.MdbxFolderFilter -> entries.filter {
                it.matchesMdbxFolder(filter.databaseId, filter.folderId)
            }
        }
    }

    private fun PasswordEntry.matchesMdbxFolder(databaseId: Long, folderId: String): Boolean {
        if (mdbxDatabaseId != databaseId) return false
        val normalizedFolderId = folderId.trim()
        val explicitFolderId = mdbxFolderId?.trim().orEmpty()
        if (normalizedFolderId.equals("root", ignoreCase = true)) {
            return explicitFolderId.isBlank() && categoryId == null
        }
        if (explicitFolderId.isNotBlank()) {
            return explicitFolderId == normalizedFolderId
        }
        val categoryIdFromFolder = normalizedFolderId
            .removePrefix("category:")
            .takeIf { it != normalizedFolderId }
            ?.toLongOrNull()
        return categoryIdFromFolder != null && categoryId == categoryIdFromFolder
    }

    private fun filterGhostEntriesForDisplay(entries: List<PasswordEntry>): List<PasswordEntry> {
        val ghostIds = findGhostDisplayIds(
            entries = entries,
            idOf = PasswordEntry::id,
            groupKeyOf = ::buildGhostGroupKey,
            isPasswordMode = { entry ->
                entry.loginType.equals("PASSWORD", ignoreCase = true)
            },
            shouldFilterGhost = { entry ->
                !entry.isLocalOnlyEntry() || entry.hasOwnershipConflict()
            },
            resolveSecret = { entry -> inspectSecretState(entry).plainValueOrEmpty() },
        )
        if (ghostIds.isEmpty()) return entries
        return entries.filterNot { it.id in ghostIds }
    }

    /**
     * Collapse exact duplicated entries caused by repeated sync/import records.
     * We keep one best row for each identical source+account+password signature.
     */
    private fun dedupeExactEntries(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.size <= 1) return entries

        val indexById = entries.mapIndexed { index, entry -> entry.id to index }.toMap()
        val deduped = entries
            .groupBy { buildExactDisplayKey(it) }
            .values
            .mapNotNull { pickBestEntry(it) }

        return deduped.sortedBy { indexById[it.id] ?: Int.MAX_VALUE }
    }

    private fun buildExactDisplayKey(entry: PasswordEntry): String {
        val stableIdentity = passwordDisplayStableIdentityKey(entry)
        // The replica group may contain intentional same-target multi-password
        // siblings. The smart path handles cross-target replicas; this exact
        // fallback must keep each persisted sibling visible.
        val sourceKey = if (stableIdentity.isNullOrBlank() || stableIdentity.startsWith("replica:")) {
            "row:${entry.id}"
        } else {
            stableIdentity
        }

        return listOf(
            sourceKey,
            normalizeComparableText(entry.title),
            normalizeComparableText(entry.username),
            normalizeWebsiteForGhostGrouping(entry.website),
            entry.password
        ).joinToString("|")
    }

    private fun buildGhostGroupKey(entry: PasswordEntry): String {
        val sourceKey = when (val ownership = entry.resolveOwnership()) {
            is PasswordOwnership.Conflict ->
                "conflict:${entry.keepassDatabaseId}:${entry.bitwardenVaultId}:${entry.keepassEntryUuid.orEmpty()}:${entry.bitwardenCipherId.orEmpty()}"
            is PasswordOwnership.Bitwarden ->
                if (!entry.bitwardenCipherId.isNullOrBlank()) {
                    "bw:${ownership.vaultId}:${entry.bitwardenCipherId}"
                } else {
                    "bw-local:${ownership.vaultId}:${entry.bitwardenFolderId.orEmpty()}"
                }
            is PasswordOwnership.KeePass ->
                "kp:${ownership.databaseId}:${entry.keepassGroupPath.orEmpty()}"
            is PasswordOwnership.Mdbx ->
                "mdbx:${ownership.databaseId}"
            PasswordOwnership.MonicaLocal -> "local"
        }

        val title = normalizeComparableText(entry.title)
        val username = normalizeComparableText(entry.username)
        val website = normalizeWebsiteForGhostGrouping(entry.website)
        return "$sourceKey|$title|$website|$username"
    }

    private fun normalizeWebsiteForGhostGrouping(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        return raw
            .lowercase(Locale.ROOT)
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    private fun pickBestEntry(candidates: List<PasswordEntry>): PasswordEntry? {
        return candidates.maxWithOrNull(
            compareBy<PasswordEntry> { it.notes.length }
                .thenBy { it.website.length }
                .thenBy { it.username.length }
                .thenBy { if (it.isFavorite) 1 else 0 }
                .thenBy { if (it.hasOwnershipConflict()) 2 else if (!it.isLocalOnlyEntry()) 1 else 0 }
                .thenBy { it.updatedAt.time }
        )
    }

    private data class BitwardenComparableSignature(
        val username: String,
        val title: String,
        val domain: String
    )

    /**
     * "Local only" means:
     * 1) not a KeePass item
     * 2) not an already-synced Bitwarden cipher
     * 3) no matching item exists in any Bitwarden vault
     */
    private fun filterLocalOnlyComparedToBitwarden(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.isEmpty()) return emptyList()

        val bitwardenIndexByUsername = entries
            .asSequence()
            .filter { it.keepassDatabaseId == null && it.bitwardenVaultId != null && it.bitwardenCipherId != null }
            .map {
                BitwardenComparableSignature(
                    username = normalizeComparableText(it.username),
                    title = normalizeComparableText(it.title),
                    domain = extractComparableDomain(it.website)
                )
            }
            .filter { it.username.isNotBlank() && (it.title.isNotBlank() || it.domain.isNotBlank()) }
            .groupBy { it.username }

        return entries.filter { entry ->
            isLocalOnlyComparedToBitwarden(entry, bitwardenIndexByUsername)
        }
    }

    private fun isLocalOnlyComparedToBitwarden(
        entry: PasswordEntry,
        bitwardenIndexByUsername: Map<String, List<BitwardenComparableSignature>>
    ): Boolean {
        if (!entry.isLocalOnlyEntry()) return false
        if (entry.bitwardenCipherId != null) return false

        val username = normalizeComparableText(entry.username)
        if (username.isBlank()) return true

        val domain = extractComparableDomain(entry.website)
        val title = normalizeComparableText(entry.title)
        if (domain.isBlank() && title.isBlank()) return true

        val candidates = bitwardenIndexByUsername[username] ?: return true
        val matched = candidates.any { candidate ->
            (domain.isNotBlank() && domain == candidate.domain) ||
                (title.isNotBlank() && title == candidate.title)
        }
        return !matched
    }

    private fun normalizeComparableText(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun matchesSearchQuery(entry: PasswordEntry, query: String): Boolean {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isBlank()) return true
        return entry.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.website.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.username.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.appName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.appPackageName.lowercase(Locale.ROOT).contains(normalizedQuery)
    }

    private fun extractComparableDomain(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""

        return runCatching {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val host = URI(withScheme).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
            if (host.isNotBlank()) host else raw
                .lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .substringBefore('/')
        }.getOrElse {
            raw.lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .substringBefore('/')
        }.trim()
    }

    private fun buildDedupeKey(entry: PasswordEntry): String {
        val title = normalizeDedupeText(entry.title)
        val username = normalizeDedupeText(entry.username)
        val website = normalizeWebsiteForDedupe(entry.website)
        return "$title|$username|$website"
    }

    private fun normalizeDedupeText(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun normalizeWebsiteForDedupe(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""

        return runCatching {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val uri = URI(withScheme)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            if (host.isEmpty()) return@runCatching raw.lowercase(Locale.ROOT).trimEnd('/')

            val port = uri.port
            val hostWithPort = if (port == -1 || port == 80 || port == 443) host else "$host:$port"
            val path = (uri.path ?: "").trim().trimEnd('/').lowercase(Locale.ROOT)
            if (path.isBlank()) hostWithPort else "$hostWithPort$path"
        }.getOrElse {
            raw.lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .trimEnd('/')
        }
    }

    private fun decryptForDisplay(encryptedPassword: String): String {
        return decodePasswordOrNull(encryptedPassword).orEmpty()
    }

    fun inspectSecretState(entry: PasswordEntry): SecretValueState {
        return passwordProviderRegistry.inspectSecret(entry)
    }

    fun hasOwnershipConflict(entry: PasswordEntry): Boolean = entry.hasOwnershipConflict()

    suspend fun getRawPasswordEntryById(id: Long): PasswordEntry? {
        val entry = repository.getPasswordEntryById(id) ?: return null
        return normalizeLegacyOwnershipMetadata(entry)
    }

    suspend fun getRawActivePasswordEntries(): List<PasswordEntry> {
        val entries = repository.getAllPasswordEntries().first()
        val normalizedEntries = ArrayList<PasswordEntry>(entries.size)
        entries.forEach { entry ->
            normalizedEntries += normalizeLegacyOwnershipMetadata(entry)
        }
        return normalizedEntries
    }

    suspend fun getSecretValueStates(ids: List<Long>): Map<Long, SecretValueState> {
        return repository.getPasswordsByIds(ids).associate { entry ->
            entry.id to inspectSecretState(entry)
        }
    }

    suspend fun getBitwardenVaultCacheRiskSummary(vaultId: Long): BitwardenRepository.VaultCacheRiskSummary {
        val repositoryInstance = bitwardenRepository
            ?: throw IllegalStateException("Bitwarden repository unavailable")
        return repositoryInstance.getVaultCacheRiskSummary(vaultId)
    }

    suspend fun clearBitwardenVaultLocalCache(
        vaultId: Long,
        mode: BitwardenRepository.CacheClearMode
    ): BitwardenRepository.CacheClearResult {
        val repositoryInstance = bitwardenRepository
            ?: throw IllegalStateException("Bitwarden repository unavailable")
        val beforeEntryIds = repositoryInstance.getPasswordEntries(vaultId)
            .mapTo(linkedSetOf()) { it.id }
        val result = repositoryInstance.clearVaultLocalCache(vaultId, mode)
        if (beforeEntryIds.isNotEmpty()) {
            val afterEntryIds = repositoryInstance.getPasswordEntries(vaultId)
                .asSequence()
                .map { it.id }
                .toHashSet()
            beforeEntryIds
                .filterNot(afterEntryIds::contains)
                .forEach { entryId ->
                    bitwardenOfflineSecretCache?.clear(entryId)
                }
        }
        return result
    }

    private fun decodePasswordOrNull(rawPassword: String): String? {
        if (rawPassword.isEmpty()) return ""
        return try {
            unwrapPasswordLayersForDisplay(rawPassword)
        } catch (error: Exception) {
            val forcedReauth = securityManager.handleVaultDecryptFailure(error)
            if (forcedReauth) {
                _isAuthenticated.value = false
            }
            if (!hasLoggedDecryptAuthStateWarning) {
                Log.w(
                    "PasswordViewModel",
                    "Skip decrypt due to auth/key state: ${error.message}, forcedReauth=$forcedReauth"
                )
                hasLoggedDecryptAuthStateWarning = true
            }
            null
        }
    }

    private fun loadBitwardenOfflineCachedSecret(entry: PasswordEntry): String? {
        return bitwardenOfflineSecretCache?.recall(entry)
    }

    private fun rememberBitwardenOfflineCachedSecret(entry: PasswordEntry, plainSecret: String) {
        if (plainSecret.isBlank()) return
        bitwardenOfflineSecretCache?.remember(entry, plainSecret)
    }

    suspend fun clearBitwardenOfflineSecretCacheForVault(vaultId: Long): Int {
        val cache = bitwardenOfflineSecretCache ?: return 0
        val entries = bitwardenRepository?.getPasswordEntries(vaultId).orEmpty()
        entries.forEach { entry -> cache.clear(entry.id) }
        return entries.size
    }

    private suspend fun warmupBitwardenOfflineSecretCache() {
        val entries = repository.getAllPasswordEntries().first()
        rememberDecodedBitwardenSecrets(entries)
    }

    private fun rememberDecodedBitwardenSecrets(entries: List<PasswordEntry>): Int {
        val cache = bitwardenOfflineSecretCache ?: return 0
        var warmedCount = 0
        entries.forEach { entry ->
            if (!entry.hasBitwardenCipherBinding() || entry.password.isBlank()) return@forEach
            val decoded = decodePasswordOrNull(entry.password)
            if (!decoded.isNullOrBlank()) {
                cache.remember(entry, decoded)
                warmedCount += 1
            }
        }
        return warmedCount
    }

    private suspend fun repairLegacyDetachedKeePassEntries() {
        val entries = repository.getAllPasswordEntries().first()
        val staleIds = mutableListOf<Long>()
        entries.forEach { entry ->
            if (isLegacyDetachedKeePassEntry(entry)) {
                staleIds += entry.id
            }
        }
        if (staleIds.isEmpty()) return

        repository.updateKeePassDatabaseForPasswords(staleIds, null)
        Log.i(
            "PasswordViewModel",
            "Detached legacy KeePass-local password bindings: count=${staleIds.size}"
        )
    }

    private suspend fun repairLegacyOwnershipConflicts() {
        val entries = repository.getAllPasswordEntries().first()
        var repairedCount = 0

        entries.forEach { entry ->
            if (!entry.hasOwnershipConflict()) return@forEach
            val normalized = normalizeLegacyOwnershipConflictEntry(entry)
            if (normalized != entry) {
                repairedCount++
            }
        }

        if (repairedCount > 0) {
            Log.i(
                "PasswordViewModel",
                "Repaired legacy ownership conflicts: count=$repairedCount"
            )
        }
    }

    private suspend fun normalizeLegacyOwnershipMetadata(entry: PasswordEntry): PasswordEntry {
        val keePassNormalized = normalizeLegacyDetachedKeePassEntry(entry)
        return normalizeLegacyOwnershipConflictEntry(keePassNormalized)
    }

    private suspend fun normalizeLegacyOwnershipConflictEntry(entry: PasswordEntry): PasswordEntry {
        if (!entry.hasOwnershipConflict()) return entry

        val hasKeePassIdentity =
            !entry.keepassEntryUuid.isNullOrBlank() ||
                !entry.keepassGroupUuid.isNullOrBlank() ||
                !entry.keepassGroupPath.isNullOrBlank()

        val hasBitwardenIdentity =
            !entry.bitwardenCipherId.isNullOrBlank() ||
                !entry.bitwardenRevisionDate.isNullOrBlank() ||
                entry.bitwardenLocalModified

        val normalized = when {
            hasBitwardenIdentity && !hasKeePassIdentity -> entry.clearKeePassBindingOnly()
            hasKeePassIdentity && !hasBitwardenIdentity -> entry.clearBitwardenBindingOnly()
            !hasKeePassIdentity && !hasBitwardenIdentity &&
                entry.keepassGroupPath.isNullOrBlank() &&
                entry.bitwardenFolderId.isNullOrBlank() -> {
                entry.clearKeePassBindingOnly().clearBitwardenBindingOnly()
            }

            else -> entry
        }

        if (normalized == entry) return entry

        repository.updatePasswordEntry(normalized)
        return repository.getPasswordEntryById(entry.id) ?: normalized
    }

    private fun PasswordEntry.clearKeePassBindingOnly(): PasswordEntry {
        return copy(
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null
        )
    }

    private fun PasswordEntry.clearBitwardenBindingOnly(): PasswordEntry {
        return copy(
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false
        )
    }

    private suspend fun normalizeLegacyDetachedKeePassEntry(entry: PasswordEntry): PasswordEntry {
        if (!isLegacyDetachedKeePassEntry(entry)) return entry
        repository.updateKeePassDatabaseForPasswords(listOf(entry.id), null)
        return repository.getPasswordEntryById(entry.id) ?: entry.copy(
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null
        )
    }

    private suspend fun isLegacyDetachedKeePassEntry(entry: PasswordEntry): Boolean {
        val keepassDatabaseId = entry.keepassDatabaseId ?: return false
        if (entry.bitwardenVaultId != null || !entry.bitwardenCipherId.isNullOrBlank()) return false
        if (entry.categoryId != null) return true

        val keepassDatabaseExists = localKeePassDatabaseDao
            ?.getDatabaseById(keepassDatabaseId) != null
        if (keepassDatabaseExists) return false

        return entry.keepassEntryUuid.isNullOrBlank() && entry.keepassGroupUuid.isNullOrBlank()
    }

    private fun shouldPreserveUnreadableBitwardenPassword(
        existing: PasswordEntry?,
        incomingPassword: String
    ): Boolean {
        if (existing == null || incomingPassword.isNotEmpty()) return false
        if (existing.bitwardenVaultId == null || existing.bitwardenCipherId.isNullOrBlank()) return false
        if (existing.password.isBlank()) return false
        val secretState = inspectSecretState(existing)
        return secretState is SecretValueState.Unreadable && secretState.source is PasswordSource.Bitwarden
    }

    private fun resolvePasswordForUpdate(
        existing: PasswordEntry?,
        pendingEntry: PasswordEntry,
        incomingPassword: String
    ): String {
        return passwordProviderRegistry.resolvePasswordForStorage(
            existingEntry = existing,
            pendingEntry = pendingEntry,
            incomingPassword = incomingPassword
        )
    }

    /**
     * Historical data may contain nested encrypted payloads (ciphertext saved as plaintext, then encrypted again).
     * Try a few rounds and stop once value is stable.
     */
    private fun unwrapPasswordLayersForDisplay(value: String): String {
        var current = value
        repeat(3) {
            val decrypted = synchronized(decryptLock) {
                securityManager.decryptData(current)
            }
            if (decrypted == current) return current
            current = decrypted
        }
        return current
    }

    private suspend fun decodeHistoryPasswordForDisplay(entry: PasswordHistoryEntry): String {
        val decoded = decryptForDisplay(entry.password)
        if (decoded.isBlank()) return ""

        val stableEncoded = securityManager.encryptDataLegacyCompat(decoded)
        if (stableEncoded != entry.password) {
            repository.updatePasswordHistoryPassword(entry.id, stableEncoded)
        }
        return decoded
    }

    private fun syncKeePassDatabase(databaseId: Long, forceRefresh: Boolean = false) {
        val requestId = SyncDiagnostics.nextTaskId("kp-password")
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = requestId,
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.PASSWORD, SyncItemKind.TOTP)
                    ),
                    trigger = if (forceRefresh) SyncTrigger.MANUAL else SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = if (forceRefresh) SyncPriority.MANUAL else SyncPriority.PAGE_VISIBLE,
                    mode = if (forceRefresh) SyncMode.FOREGROUND else SyncMode.SILENT,
                    throttleMs = if (forceRefresh) 0L else 30_000L
                )
            ) {
                syncKeePassDatabaseNow(
                    databaseId = databaseId,
                    forceRefresh = forceRefresh,
                    taskId = requestId
                )
            }
        }
    }

    private suspend fun syncKeePassDatabaseNow(
        databaseId: Long,
        forceRefresh: Boolean,
        taskId: String
    ) {
        val target = "keepass:password:$databaseId"
        val trigger = if (forceRefresh) "PASSWORD_MANUAL_REFRESH" else "PASSWORD_FILTER_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger, detail = "forceRefresh=$forceRefresh")
        val bridge = keepassBridge ?: run {
            SyncDiagnostics.skipped(taskId, target, trigger, "bridge_unavailable", detail = "forceRefresh=$forceRefresh")
            return
        }
        val startedAt = SyncDiagnostics.start(taskId, target, trigger, detail = "forceRefresh=$forceRefresh")
        try {
            if (forceRefresh) {
                bridge.syncLegacyRemoteDatabase(databaseId)
                    .onFailure { error ->
                        Log.w("PasswordViewModel", "KeePass remote refresh failed before projection for databaseId=$databaseId", error)
                    }
                KeePassKdbxService.invalidateProcessCache(databaseId)
            }
            val passwordDecision = bridge.legacyProjectionRefreshDecision(
                databaseId = databaseId,
                kind = takagi.ru.monica.keepass.KeePassProjectionKind.PASSWORD,
                forceRefresh = forceRefresh
            ).getOrThrow()
            val totpDecision = bridge.legacyProjectionRefreshDecision(
                databaseId = databaseId,
                kind = takagi.ru.monica.keepass.KeePassProjectionKind.TOTP,
                forceRefresh = forceRefresh
            ).getOrThrow()
            if (!passwordDecision.needsRefresh && !totpDecision.needsRefresh) {
                SyncDiagnostics.skipped(taskId, target, trigger, "native_revision_unchanged", startedAt)
                return
            }
            val snapshot = bridge
                .loadLegacyWorkspace(databaseId, allowedSecureItemTypes = setOf(ItemType.TOTP))
                .getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "workspace_unavailable", startedAt)
                    return
                }
            val indexedKinds = mutableSetOf<takagi.ru.monica.keepass.KeePassProjectionKind>()
            if (passwordDecision.needsRefresh) {
                upsertKeePassEntries(databaseId, snapshot.passwords)
                indexedKinds += takagi.ru.monica.keepass.KeePassProjectionKind.PASSWORD
            }
            if (totpDecision.needsRefresh) {
                syncKeePassTotpEntries(databaseId, snapshot.secureItems)
                indexedKinds += takagi.ru.monica.keepass.KeePassProjectionKind.TOTP
            }
            bridge.markLegacyProjectionIndexed(
                databaseId = databaseId,
                revisionToken = snapshot.sessionRevision ?: passwordDecision.revisionToken,
                kinds = indexedKinds
            )
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "passwords=${snapshot.passwords.size} secureItems=${snapshot.secureItems.size}"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            Log.w("PasswordViewModel", "KeePass sync failed for databaseId=$databaseId", error)
            throw error
        }
    }

    private suspend fun refreshAllKeePassDatabases() {
        val dao = localKeePassDatabaseDao ?: return
        dao.getAllDatabasesSync().forEach { database ->
            syncKeePassDatabase(database.id, forceRefresh = false)
        }
    }

    fun refreshKeePassFromSourceForCurrentContext() {
        val current = _categoryFilter.value
        val activeDatabaseId = when (current) {
            is CategoryFilter.KeePassDatabase -> current.databaseId
            is CategoryFilter.KeePassGroupFilter -> current.databaseId
            is CategoryFilter.KeePassDatabaseStarred -> current.databaseId
            is CategoryFilter.KeePassDatabaseUncategorized -> current.databaseId
            else -> null
        }
        val resolvedDatabaseId = activeDatabaseId ?: return
        syncKeePassDatabase(resolvedDatabaseId, forceRefresh = true)
    }

    fun syncKeePassDatabaseForVisibleVault(databaseId: Long, forceRefresh: Boolean = false) {
        KeePassKdbxService.markDatabaseActive(databaseId)
        syncKeePassDatabase(databaseId, forceRefresh = forceRefresh)
    }

    private suspend fun upsertKeePassEntries(databaseId: Long, entries: List<KeePassEntryData>) {
        val incomingEntries = entries.filter { shouldImportKeePassPasswordEntry(it) }
        val activeBefore = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId).size
        val recycleIncomingCount = incomingEntries.count { it.isInRecycleBin }
        val incomingKeys = incomingEntries
            .asSequence()
            .map { buildKeePassSyncKey(it) }
            .toSet()
        Log.i(
            "PasswordViewModel",
            "KeePass password upsert begin: databaseId=$databaseId, " +
                "raw=${entries.size}, importable=${incomingEntries.size}, " +
                "incomingRecycle=$recycleIncomingCount, activeBefore=$activeBefore, " +
                "uniqueKeys=${incomingKeys.size}"
        )

        incomingEntries.forEach { item ->
            val hasStableKeePassUuid = !item.entryUuid.isNullOrBlank()
            val isRemoteConflictReplica = isRemoteConflictReplicaTitle(item.title)
            val existingByUuid = item.entryUuid
                ?.takeIf { it.isNotBlank() }
                ?.let { repository.getPasswordEntryByKeePassUuid(databaseId, it) }
            val existingById = if (isRemoteConflictReplica) {
                null
            } else {
                item.monicaLocalId?.let { repository.getPasswordEntryById(it) }
            }
            val existing = when {
                existingByUuid != null -> existingByUuid
                existingById != null && existingById.keepassDatabaseId == databaseId -> existingById
                hasStableKeePassUuid -> null
                else -> repository.getDuplicateEntryInKeePass(
                    databaseId = databaseId,
                    title = item.title,
                    username = item.username,
                    website = item.url,
                    groupPath = item.groupPath
                )
            }
            val normalizedPassword = normalizeIncomingKeePassPassword(item.password)
            val existingPlainPassword = existing?.let { decryptForDisplay(it.password) }.orEmpty()
            val encryptedPassword = if (existing != null && normalizedPassword.isBlank()) {
                if (existingPlainPassword.isNotBlank()) {
                    Log.w(
                        "PasswordViewModel",
                        "Skip KeePass blank-password overwrite for entryId=${existing.id}, title=${existing.title}"
                    )
                    existing.password
                } else {
                    securityManager.encryptData(normalizedPassword)
                }
            } else {
                securityManager.encryptData(normalizedPassword)
            }
            val importedPlainPassword = if (existing != null && encryptedPassword == existing.password) {
                existingPlainPassword
            } else {
                normalizedPassword
            }
            if (existing != null) {
                val isInRecycleBin = item.isInRecycleBin
                val updated = existing.copy(
                    title = item.title,
                    username = item.username,
                    password = encryptedPassword,
                    website = item.url,
                    notes = item.notes,
                    appPackageName = item.appPackageName,
                    appName = item.appName,
                    email = item.email,
                    phone = item.phone,
                    addressLine = item.addressLine,
                    city = item.city,
                    state = item.state,
                    zipCode = item.zipCode,
                    country = item.country,
                    creditCardNumber = item.creditCardNumber,
                    creditCardHolder = item.creditCardHolder,
                    creditCardExpiry = item.creditCardExpiry,
                    creditCardCVV = item.creditCardCVV,
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = item.groupPath,
                    keepassEntryUuid = item.entryUuid,
                    keepassGroupUuid = item.groupUuid,
                    sshKeyData = item.sshKeyData,
                    loginType = item.loginType,
                    ssoProvider = item.ssoProvider,
                    ssoRefEntryId = item.ssoRefEntryId,
                    wifiMetadata = item.wifiMetadata,
                    isDeleted = isInRecycleBin,
                    deletedAt = if (isInRecycleBin) (existing.deletedAt ?: Date()) else null,
                    updatedAt = Date()
                )
                if (!existing.matchesKeePassImport(updated, importedPlainPassword)) {
                    repository.updatePasswordEntry(updated)
                }
                saveKeePassCustomFields(existing.id, item)
            } else {
                val isInRecycleBin = item.isInRecycleBin
                val newEntry = PasswordEntry(
                    title = item.title,
                    username = item.username,
                    password = encryptedPassword,
                    website = item.url,
                    notes = item.notes,
                    appPackageName = item.appPackageName,
                    appName = item.appName,
                    email = item.email,
                    phone = item.phone,
                    addressLine = item.addressLine,
                    city = item.city,
                    state = item.state,
                    zipCode = item.zipCode,
                    country = item.country,
                    creditCardNumber = item.creditCardNumber,
                    creditCardHolder = item.creditCardHolder,
                    creditCardExpiry = item.creditCardExpiry,
                    creditCardCVV = item.creditCardCVV,
                    createdAt = Date(),
                    updatedAt = Date(),
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = item.groupPath,
                    keepassEntryUuid = item.entryUuid,
                    keepassGroupUuid = item.groupUuid,
                    sshKeyData = item.sshKeyData,
                    loginType = item.loginType,
                    ssoProvider = item.ssoProvider,
                    ssoRefEntryId = item.ssoRefEntryId,
                    wifiMetadata = item.wifiMetadata,
                    isDeleted = isInRecycleBin,
                    deletedAt = if (isInRecycleBin) Date() else null
                )
                val insertedId = repository.insertPasswordEntry(newEntry)
                saveKeePassCustomFields(insertedId, item)
            }
        }

        val staleCount = reconcileKeePassEntries(databaseId, incomingKeys)
        val activeAfter = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId).size
        Log.i(
            "PasswordViewModel",
            "KeePass password upsert end: databaseId=$databaseId, " +
                "raw=${entries.size}, importable=${incomingEntries.size}, " +
                "incomingRecycle=$recycleIncomingCount, staleRemoved=$staleCount, " +
                "activeBefore=$activeBefore, activeAfter=$activeAfter"
        )
    }

    private fun PasswordEntry.matchesKeePassImport(
        imported: PasswordEntry,
        importedPlainPassword: String
    ): Boolean {
        return copy(password = "", updatedAt = imported.updatedAt) ==
            imported.copy(password = "") &&
            decryptForDisplay(password) == importedPlainPassword
    }

    private suspend fun saveKeePassCustomFields(entryId: Long, item: KeePassEntryData) {
        val fieldRepository = customFieldRepository ?: return
        val fields = item.customFields.map { field ->
            CustomField(
                entryId = entryId,
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = field.sortOrder
            )
        }
        if (fieldRepository.getFieldsByEntryIdSync(entryId).matchesKeePassCustomFields(fields)) {
            return
        }
        fieldRepository.saveFieldsForEntries(mapOf(entryId to fields))
    }

    private fun List<CustomField>.matchesKeePassCustomFields(imported: List<CustomField>): Boolean {
        return toKeePassCustomFieldFingerprints() == imported.toKeePassCustomFieldFingerprints()
    }

    private fun List<CustomField>.toKeePassCustomFieldFingerprints(): List<KeePassCustomFieldFingerprint> {
        return mapIndexed { index, field ->
            KeePassCustomFieldFingerprint(
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }
    }


    private suspend fun resolveKeePassCustomFieldsForSync(
        entryId: Long,
        customFieldsOverride: List<CustomFieldDraft>?
    ): List<KeePassCustomFieldData> {
        customFieldsOverride?.let { drafts ->
            return drafts
                .filter { it.shouldPersist() }
                .mapIndexed { index, field ->
                    KeePassCustomFieldData(
                        title = field.title,
                        value = field.value,
                        isProtected = field.isProtected,
                        sortOrder = index
                    )
                }
        }

        if (entryId <= 0) return emptyList()
        val fieldRepository = customFieldRepository ?: return emptyList()
        return fieldRepository.getFieldsByEntryIdSync(entryId)
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, field ->
                KeePassCustomFieldData(
                    title = field.title,
                    value = field.value,
                    isProtected = field.isProtected,
                    sortOrder = index
                )
            }
    }

    private suspend fun syncKeePassTotpEntries(
        databaseId: Long,
        snapshots: List<KeePassSecureItemData>? = null
    ) {
        val secureRepo = secureItemRepository ?: return

        val resolvedSnapshots = snapshots ?: keepassBridge
            ?.readLegacySecureItems(databaseId, setOf(ItemType.TOTP))
            ?.getOrThrow()
            ?: return

        val existingTotp = secureRepo.getItemsByType(ItemType.TOTP).first()
        val claimedProjectionIds = mutableSetOf<Long>()
        resolvedSnapshots.forEach { snapshot ->
            val incoming = snapshot.item
            val existingByUuid = incoming.keepassEntryUuid
                ?.takeIf { it.isNotBlank() }
                ?.let { entryUuid -> secureRepo.getItemByKeePassUuid(databaseId, entryUuid) }
            val existingBySource = snapshot.sourceMonicaId
                ?.takeIf { it > 0 }
                ?.let { sourceId -> secureRepo.getItemById(sourceId) }
                ?.takeIf { it.itemType == ItemType.TOTP }
            val incomingIdentityKey = parseStoredTotpData(incoming)?.let(::buildTotpCopyIdentityKey)
            val existing = KeePassTotpProjectionMatcher.findExistingProjection(
                databaseId = databaseId,
                incoming = incoming,
                existingTotp = existingTotp,
                existingByUuid = existingByUuid,
                existingBySource = existingBySource,
                incomingIdentityKey = incomingIdentityKey,
                identityKeyOf = { candidate -> parseStoredTotpData(candidate)?.let(::buildTotpCopyIdentityKey) },
                claimedProjectionIds = claimedProjectionIds,
            )
            existing?.let { claimedProjectionIds += it.id }

                if (existing == null) {
                    secureRepo.insertItem(incoming)
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
                        secureRepo.updateItem(updated)
                    }
                }
        }
    }

    private fun SecureItem.matchesKeePassSecureItemImport(imported: SecureItem): Boolean {
        return copy(itemData = "", updatedAt = imported.updatedAt) == imported.copy(itemData = "") &&
            decryptStoredSensitiveValue(itemData) == decryptStoredSensitiveValue(imported.itemData)
    }

    private fun normalizeIncomingKeePassPassword(raw: String): String {
        if (raw.isBlank()) return raw
        var current = raw
        repeat(3) {
            val decrypted = runCatching {
                synchronized(decryptLock) {
                    securityManager.decryptData(current)
                }
            }.getOrNull() ?: return current
            if (decrypted == current) return current
            current = decrypted
        }
        return current
    }

    private fun shouldImportKeePassPasswordEntry(item: KeePassEntryData): Boolean {
        if (SteamExternalMaFileContract.isMarked(item.customFields.map { it.title to it.value })) {
            // Steam maFile entries are owned by the Steam page and remain in the KDBX file.
            // Keeping a Room mirror would expose the same authenticator as a normal password.
            return false
        }
        // KeePass 纯模板条目已在解析层过滤。
        // 这里保留“只有标题”的真实条目，避免误伤用户手工维护的极简记录。
        return item.title.isNotBlank() ||
            item.username.isNotBlank() ||
            item.password.isNotBlank() ||
            item.url.isNotBlank() ||
            item.notes.isNotBlank()
    }

    private fun isRemoteConflictReplicaTitle(title: String): Boolean {
        return title.contains("[远端冲突副本]")
    }

    private fun buildKeePassSyncKey(
        title: String,
        username: String,
        website: String,
        groupPath: String?
    ): String {
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        val normalizedUsername = username.trim().lowercase(Locale.ROOT)
        val normalizedWebsite = normalizeWebsiteForDedupe(website)
        val normalizedGroup = groupPath?.trim().orEmpty()
        return "$normalizedGroup|$normalizedTitle|$normalizedUsername|$normalizedWebsite"
    }

    private fun buildKeePassSyncKey(item: KeePassEntryData): String {
        val entryUuid = item.entryUuid?.trim().orEmpty()
        if (entryUuid.isNotEmpty()) {
            return "uuid:${entryUuid.lowercase(Locale.ROOT)}"
        }
        return buildKeePassSyncKey(item.title, item.username, item.url, item.groupPath)
    }

    private fun buildKeePassSyncKey(entry: PasswordEntry): String {
        val entryUuid = entry.keepassEntryUuid?.trim().orEmpty()
        if (entryUuid.isNotEmpty()) {
            return "uuid:${entryUuid.lowercase(Locale.ROOT)}"
        }
        return buildKeePassSyncKey(entry.title, entry.username, entry.website, entry.keepassGroupPath)
    }

    private suspend fun reconcileKeePassEntries(databaseId: Long, incomingKeys: Set<String>): Int {
        val localEntries = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId)
        if (localEntries.isEmpty()) return 0

        val grouped = localEntries.groupBy { entry -> buildKeePassSyncKey(entry) }

        val keepIds = mutableSetOf<Long>()
        grouped.forEach { (key, candidates) ->
            if (key !in incomingKeys) return@forEach
            val keep = candidates.maxWithOrNull(
                compareBy<PasswordEntry> { if (decryptForDisplay(it.password).isNotBlank()) 1 else 0 }
                    .thenBy { it.updatedAt.time }
                    .thenBy { it.id }
            ) ?: candidates.first()
            keepIds += keep.id
        }

        val stale = localEntries.filter { entry ->
            val key = buildKeePassSyncKey(entry)
            key !in incomingKeys || entry.id !in keepIds
        }

        stale.forEach { repository.deletePasswordEntry(it) }
        if (stale.isNotEmpty()) {
            Log.i(
                "PasswordViewModel",
                "KeePass password reconcile removed stale active rows: databaseId=$databaseId, " +
                    "localActive=${localEntries.size}, incomingKeys=${incomingKeys.size}, stale=${stale.size}"
            )
        }
        return stale.size
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(filter: CategoryFilter) {
        if (filter is CategoryFilter.Archived) {
            openArchiveView()
            return
        }
        archiveFilterController.clear()
        applyCategoryFilter(filter, persist = true)
    }

    fun openArchiveView() {
        val archiveFilter = archiveFilterController.open(_categoryFilter.value)
        applyCategoryFilter(archiveFilter, persist = false)
    }

    fun closeArchiveView() {
        applyCategoryFilter(archiveFilterController.close(), persist = true)
    }

    private fun applyCategoryFilter(filter: CategoryFilter, persist: Boolean) {
        _categoryFilter.value = filter
        if (persist) {
            persistCategoryFilter(filter)
        }
        when (filter) {
            is CategoryFilter.KeePassDatabase -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassGroupFilter -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassDatabaseStarred -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            else -> KeePassKdbxService.trimInactiveCaches()
        }
    }

    private fun restoreLastCategoryFilter() {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            runCatching { manager.settingsFlow.first() }
                .onSuccess { settings ->
                    if (_categoryFilter.value !is CategoryFilter.All) return@onSuccess
                    val restoredFilter = decodeSavedCategoryFilter(settings)
                    val sanitizedFilter = sanitizeRestoredCategoryFilter(restoredFilter)
                    if (sanitizedFilter != restoredFilter) {
                        applyCategoryFilter(CategoryFilter.All, persist = true)
                    } else {
                        applyCategoryFilter(sanitizedFilter, persist = false)
                    }
                }
                .onFailure { error ->
                    Log.w("PasswordViewModel", "Failed to restore last category filter", error)
                }
        }
    }

    private suspend fun sanitizeRestoredCategoryFilter(filter: CategoryFilter): CategoryFilter {
        if (filter is CategoryFilter.Custom) {
            return if (repository.getCategoryById(filter.categoryId) == null) {
                CategoryFilter.All
            } else {
                filter
            }
        }

        val keepassDatabaseId = when (filter) {
            is CategoryFilter.KeePassDatabase -> filter.databaseId
            is CategoryFilter.KeePassGroupFilter -> filter.databaseId
            is CategoryFilter.KeePassDatabaseStarred -> filter.databaseId
            is CategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
            else -> null
        } ?: return filter

        val dao = localKeePassDatabaseDao ?: return CategoryFilter.All
        return if (dao.getDatabaseById(keepassDatabaseId) == null) CategoryFilter.All else filter
    }

    private fun observeInvalidCustomCategoryFilter() {
        viewModelScope.launch {
            combine(_categoryFilter, categories) { filter, categoryList ->
                filter to categoryList
            }.collectLatest { (filter, categoryList) ->
                val customFilter = filter as? CategoryFilter.Custom ?: return@collectLatest
                if (categoryList.any { it.id == customFilter.categoryId }) return@collectLatest

                val existsInDb = repository.getCategoryById(customFilter.categoryId) != null
                if (!existsInDb &&
                    _categoryFilter.value is CategoryFilter.Custom &&
                    (_categoryFilter.value as CategoryFilter.Custom).categoryId == customFilter.categoryId
                ) {
                    applyCategoryFilter(CategoryFilter.All, persist = true)
                }
            }
        }
    }

    private fun decodeSavedCategoryFilter(settings: takagi.ru.monica.data.AppSettings): CategoryFilter {
        val type = settings.lastPasswordCategoryFilterType.lowercase(Locale.ROOT)
        return when (type) {
            SAVED_FILTER_ALL -> CategoryFilter.All
            SAVED_FILTER_ARCHIVED -> CategoryFilter.Archived
            SAVED_FILTER_LOCAL -> CategoryFilter.Local
            SAVED_FILTER_LOCAL_ONLY -> CategoryFilter.LocalOnly
            SAVED_FILTER_STARRED -> CategoryFilter.Starred
            SAVED_FILTER_UNCATEGORIZED -> CategoryFilter.Uncategorized
            SAVED_FILTER_LOCAL_STARRED -> CategoryFilter.LocalStarred
            SAVED_FILTER_LOCAL_UNCATEGORIZED -> CategoryFilter.LocalUncategorized
            SAVED_FILTER_CUSTOM -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.Custom(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabase(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE_STARRED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabaseStarred(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabaseUncategorized(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_GROUP -> {
                val databaseId = settings.lastPasswordCategoryFilterPrimaryId
                val groupPath = settings.lastPasswordCategoryFilterText
                if (databaseId != null && !groupPath.isNullOrBlank()) {
                    CategoryFilter.KeePassGroupFilter(
                        databaseId = databaseId,
                        groupPath = groupPath,
                        groupUuid = settings.lastPasswordCategoryFilterGroupUuid
                    )
                } else {
                    CategoryFilter.All
                }
            }
            SAVED_FILTER_BITWARDEN_VAULT -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVault(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_VAULT_STARRED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVaultStarred(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVaultUncategorized(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_FOLDER -> {
                val vaultId = settings.lastPasswordCategoryFilterSecondaryId
                    ?: settings.lastPasswordCategoryFilterPrimaryId
                val folderId = settings.lastPasswordCategoryFilterText
                if (vaultId != null && !folderId.isNullOrBlank()) {
                    CategoryFilter.BitwardenFolderFilter(folderId, vaultId)
                } else {
                    CategoryFilter.All
                }
            }
            SAVED_FILTER_MDBX_DATABASE -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.MdbxDatabase(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_MDBX_FOLDER -> {
                val databaseId = settings.lastPasswordCategoryFilterPrimaryId
                    ?: settings.lastPasswordCategoryFilterSecondaryId
                val folderId = settings.lastPasswordCategoryFilterText
                if (databaseId != null && !folderId.isNullOrBlank()) {
                    CategoryFilter.MdbxFolderFilter(databaseId, folderId)
                } else {
                    CategoryFilter.All
                }
            }
            else -> CategoryFilter.All
        }
    }

    private fun persistCategoryFilter(filter: CategoryFilter) {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            runCatching {
                when (filter) {
                    is CategoryFilter.All -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_ALL
                    )
                    is CategoryFilter.Archived -> Unit
                    is CategoryFilter.Local -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL
                    )
                    is CategoryFilter.LocalOnly -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_ONLY
                    )
                    is CategoryFilter.Starred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_STARRED
                    )
                    is CategoryFilter.Uncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_UNCATEGORIZED
                    )
                    is CategoryFilter.LocalStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_STARRED
                    )
                    is CategoryFilter.LocalUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_UNCATEGORIZED
                    )
                    is CategoryFilter.Custom -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_CUSTOM,
                        primaryId = filter.categoryId
                    )
                    is CategoryFilter.KeePassDatabase -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassDatabaseStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE_STARRED,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassDatabaseUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassGroupFilter -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_GROUP,
                        primaryId = filter.databaseId,
                        text = filter.groupPath,
                        groupUuid = filter.groupUuid
                    )
                    is CategoryFilter.BitwardenVault -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenVaultStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT_STARRED,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenVaultUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenFolderFilter -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_FOLDER,
                        secondaryId = filter.vaultId,
                        text = filter.folderId
                    )
                    is CategoryFilter.MdbxDatabase -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_MDBX_DATABASE,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.MdbxFolderFilter -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_MDBX_FOLDER,
                        primaryId = filter.databaseId,
                        text = filter.folderId
                    )
                }
            }.onFailure { error ->
                Log.w("PasswordViewModel", "Failed to persist category filter", error)
            }
        }
    }

    fun addCategory(name: String, onResult: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.insertCategory(Category(name = name))
            onResult(id)
        }
    }

    suspend fun ensureLocalCategoryAwait(name: String): Long = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Category name is blank" }
        repository.getAllCategories().first()
            .firstOrNull { category ->
                category.mdbxDatabaseId == null &&
                    category.name.equals(normalizedName, ignoreCase = true)
            }
            ?.id
            ?: repository.insertCategory(Category(name = normalizedName))
    }

    suspend fun listMdbxFoldersAwait(databaseId: Long): List<MdbxStoredFolderEntry> =
        withContext(Dispatchers.IO) { repository.listMdbxFolders(databaseId) }

    suspend fun ensureMdbxFolderPathAwait(
        databaseId: Long,
        parentFolderId: String?,
        segments: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var currentParentId = parentFolderId?.takeIf { it.isNotBlank() } ?: "root"
            val folders = repository.listMdbxFolders(databaseId).toMutableList()
            segments.map(String::trim).filter(String::isNotBlank).forEach { segment ->
                val existing = folders.firstOrNull { folder ->
                    normalizeMdbxTransferParent(folder.parentFolderId) ==
                        normalizeMdbxTransferParent(currentParentId) &&
                        folder.name.equals(segment, ignoreCase = true)
                }
                val resolved = existing ?: repository.createMdbxFolder(
                    databaseId = databaseId,
                    name = segment,
                    parentFolderId = currentParentId
                ) ?: throw IllegalStateException("MDBX repository unavailable")
                if (existing == null) folders += resolved
                currentParentId = resolved.folderId
            }
            currentParentId
        }.also { result ->
            if (result.isSuccess) refreshMdbxFolders(databaseId)
        }
    }

    fun createMdbxFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String? = "root",
        onResult: (Result<MdbxStoredFolderEntry>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.createMdbxFolder(databaseId, name, parentFolderId)
                        ?: throw IllegalStateException("MDBX repository unavailable")
                }
            }
            refreshMdbxFolders(databaseId)
            onResult(result)
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch {
            repository.updateCategory(category)
        }
    }

    fun deleteMdbxFolder(
        databaseId: Long,
        folderId: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.deleteMdbxFolder(databaseId, folderId) }
            }
            if (result.isSuccess) refreshMdbxFolders(databaseId)
            onResult(result)
        }
    }

    internal suspend fun updateCategoriesAwait(categories: List<Category>) {
        categories.forEach { category -> repository.updateCategory(category) }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            if (_categoryFilter.value is CategoryFilter.Custom && (_categoryFilter.value as CategoryFilter.Custom).categoryId == category.id) {
                applyCategoryFilter(CategoryFilter.All, persist = true)
            }
        }
    }

    internal suspend fun deleteCategoriesAwait(categories: List<Category>) {
        if (categories.isEmpty()) return
        categories.forEach { category -> repository.deleteCategory(category) }
        val deletedIds = categories.mapTo(mutableSetOf(), Category::id)
        val selectedCategoryId = (_categoryFilter.value as? CategoryFilter.Custom)?.categoryId
        if (selectedCategoryId in deletedIds) {
            applyCategoryFilter(CategoryFilter.All, persist = true)
        }
    }

    internal suspend fun getAllActiveSecureItemsAwait(): List<SecureItem> =
        secureItemRepository?.getAllItems()?.first()?.filterNot(SecureItem::isDeleted).orEmpty()

    internal suspend fun getInactiveCategoryReferencesAwait(
        categoryIds: Set<Long>,
    ): Int {
        if (categoryIds.isEmpty()) return 0
        val archivedPasswords = repository.getArchivedEntries().first()
            .count { entry -> entry.categoryId in categoryIds }
        val deletedPasswords = repository.getDeletedEntries()
            .count { entry -> entry.categoryId in categoryIds }
        val deletedSecureItems = secureItemRepository?.getDeletedItemsSync()
            ?.count { item -> item.categoryId in categoryIds }
            ?: 0
        return archivedPasswords + deletedPasswords + deletedSecureItems
    }

    internal suspend fun copyLocalOnlySecureItemsToMonicaCategoryBatch(
        items: List<SecureItem>,
        categoryId: Long?,
    ): MdbxSecureItemBatchResult {
        val secureRepository = secureItemRepository
            ?: return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        if (items.isEmpty()) return MdbxSecureItemBatchResult(0, 0)
        val localItems = items.filter { item ->
            !item.isDeleted &&
                item.keepassDatabaseId == null &&
                item.bitwardenVaultId == null &&
                item.mdbxDatabaseId == null
        }
        if (localItems.isEmpty()) {
            return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }
        return runCatching {
            val now = Date()
            val copies = localItems.map { source ->
                source.copy(
                    id = 0,
                    categoryId = categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    mdbxDatabaseId = null,
                    mdbxFolderId = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    syncStatus = "NONE",
                    replicaGroupId = null,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val ids = secureRepository.insertItems(copies)
            MdbxSecureItemBatchResult(
                successCount = ids.size,
                failedCount = (items.size - ids.size).coerceAtLeast(0),
                createdIds = ids,
            )
        }.getOrElse { error ->
            Log.e("PasswordViewModel", "Local secure-item category copy failed", error)
            MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }
    }
    
    fun updateCategorySortOrder(categories: List<Category>) {
        viewModelScope.launch {
            categories.forEachIndexed { index, category ->
                repository.updateCategorySortOrder(category.id, index)
            }
        }
    }

    fun movePasswordsToCategory(ids: List<Long>, categoryId: Long?) {
        viewModelScope.launch {
            movePasswordsToCategoryAwait(ids, categoryId)
        }
    }

    suspend fun movePasswordsToCategoryAwait(ids: List<Long>, categoryId: Long?) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
        repository.updateCategoryForPasswords(ids, categoryId)
        // Moving a password to a Monica category must stay local-only.
        // Category linkage may be used by other sync workflows, but it must not
        // silently convert password ownership during a local move action.
        repository.updateKeePassDatabaseForPasswords(ids, null)
        deleteMovedKeePassPasswordSources(entries, "category")
    }

    suspend fun moveKeePassPasswordsToMonicaCategoryAwait(
        ids: List<Long>,
        categoryId: Long?
    ): Result<Int> {
        if (ids.isEmpty()) return Result.success(0)
        return runCatching {
            val entries = repository.getPasswordsByIds(ids)
            val keepassEntries = entries.filter { it.keepassDatabaseId != null }
            if (keepassEntries.isEmpty()) return@runCatching 0

            repository.updateCategoryForPasswords(keepassEntries.map { it.id }, categoryId)
            repository.updateKeePassDatabaseForPasswords(keepassEntries.map { it.id }, null)

            val sourceDeleted = deleteMovedKeePassPasswordSources(keepassEntries, "monica_local")
            if (!sourceDeleted) {
                throw IllegalStateException("KeePass source cleanup failed after moving password to Monica local")
            }
            keepassEntries.size
        }
    }
    
    fun movePasswordsToKeePassDatabase(ids: List<Long>, databaseId: Long?) {
        viewModelScope.launch {
            movePasswordsToKeePassDatabaseAwait(ids, databaseId)
        }
    }

    suspend fun movePasswordsToKeePassDatabaseAwait(ids: List<Long>, databaseId: Long?) {
        if (ids.isEmpty()) return
        if (databaseId != null && !canWriteKeePassDatabase(databaseId)) {
            Log.w("PasswordViewModel", "movePasswordsToKeePassDatabase blocked because KeePass target is unavailable")
            return
        }
        movePasswordsToKeePassInternal(
            ids = ids,
            buildUpdatedEntry = { entry ->
                if (databaseId == null) {
                    entry.copy(
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        keepassEntryUuid = null,
                        keepassGroupUuid = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null,
                        bitwardenCipherId = null,
                        bitwardenRevisionDate = null,
                        bitwardenLocalModified = false,
                        updatedAt = Date()
                    )
                } else {
                    KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                        entry = entry,
                        databaseId = databaseId,
                        groupPath = null
                    ).copy(updatedAt = Date())
                }
            }
        )
    }

    fun movePasswordsToKeePassGroup(ids: List<Long>, databaseId: Long, groupPath: String) {
        viewModelScope.launch {
            movePasswordsToKeePassGroupAwait(ids, databaseId, groupPath)
        }
    }

    suspend fun movePasswordsToKeePassGroupAwait(ids: List<Long>, databaseId: Long, groupPath: String) {
        if (ids.isEmpty()) return
        if (!canWriteKeePassDatabase(databaseId)) {
            Log.w("PasswordViewModel", "movePasswordsToKeePassGroup blocked because KeePass target is unavailable")
            return
        }
        movePasswordsToKeePassInternal(
            ids = ids,
            buildUpdatedEntry = { entry ->
                KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                    entry = entry,
                    databaseId = databaseId,
                    groupPath = groupPath
                ).copy(updatedAt = Date())
            }
        )
    }

    /**
     * Updates only Monica's searchable Room projection after the raw KDBX move
     * has already completed in [LocalKeePassViewModel]. This must not write the
     * KDBX again, otherwise a lossless native move can be replaced by a second
     * projection-built entry with a different UUID.
     */
    suspend fun finalizePasswordsWrittenToKeePassAwait(
        targetEntryUuidsByPasswordId: Map<Long, String>,
        databaseId: Long,
        groupPath: String?
    ) {
        if (targetEntryUuidsByPasswordId.isEmpty()) return
        val entries = repository.getPasswordsByIds(targetEntryUuidsByPasswordId.keys.toList())
        entries.forEach { entry ->
            val targetEntryUuid = targetEntryUuidsByPasswordId[entry.id]
                ?: return@forEach

            if (entry.hasBitwardenCipherBinding()) {
                val vaultId = entry.bitwardenVaultId
                    ?: throw IllegalStateException("Bitwarden vault binding is missing")
                val cipherId = entry.bitwardenCipherId
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Bitwarden cipher binding is missing")
                val queueResult = bitwardenRepository?.queueCipherDelete(
                    vaultId = vaultId,
                    cipherId = cipherId,
                    entryId = entry.id
                ) ?: Result.failure(IllegalStateException(strings.get(R.string.entry_message_bitwarden_unavailable)))
                if (queueResult.isFailure) {
                    throw queueResult.exceptionOrNull()
                        ?: IllegalStateException(strings.get(R.string.bitwarden_message_delete_queue_failed))
                }
            }

            val updatedEntry = KeePassCrossDatabaseTransfer
                .bindPasswordProjectionAfterNativeMove(
                    entry = entry,
                    databaseId = databaseId,
                    groupPath = groupPath,
                    targetEntryUuid = targetEntryUuid
                )
                .copy(updatedAt = Date())
            repository.updatePasswordEntry(updatedEntry)

            if (entry.mdbxDatabaseId != null) {
                appContext?.let { context ->
                    runCatching {
                        AttachmentContainer.facade(context).removeAttachmentsFromMdbxSource(entry)
                    }.onFailure { error ->
                        Log.e(
                            "PasswordViewModel",
                            "Failed to remove MDBX attachment mirrors after KeePass move: ${error.message}"
                        )
                    }
                }
            }
        }
    }

    fun movePasswordsToMdbxDatabase(ids: List<Long>, databaseId: Long?, folderId: String? = null) {
        viewModelScope.launch {
            movePasswordsToMdbxDatabaseAwait(ids, databaseId, folderId)
        }
    }

    suspend fun movePasswordsToMdbxDatabaseAwait(ids: List<Long>, databaseId: Long?, folderId: String? = null) {
        if (ids.isEmpty()) return
        val targetId = databaseId ?: return
        movePasswordsToMdbxFoldersAwait(
            databaseId = targetId,
            folderIdsByPasswordId = ids.associateWith { folderId }
        )
    }

    suspend fun movePasswordsToMdbxFoldersAwait(
        databaseId: Long,
        folderIdsByPasswordId: Map<Long, String?>
    ) {
        if (folderIdsByPasswordId.isEmpty()) return
        val ids = folderIdsByPasswordId.keys.toList()
        val entries = repository.getPasswordsByIds(ids)
        val now = Date()
        val targetEntries = entries.map { entry ->
            entry.copy(
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = databaseId,
                mdbxFolderId = folderIdsByPasswordId[entry.id],
                bitwardenVaultId = null,
                bitwardenFolderId = null,
                bitwardenCipherId = null,
                bitwardenRevisionDate = null,
                bitwardenLocalModified = false,
                updatedAt = now
            )
        }
        repository.updatePasswordEntries(targetEntries)
        val movedEntriesById = repository.getPasswordsByIds(ids).associateBy(PasswordEntry::id)
        val facade = appContext?.let(AttachmentContainer::facade)
        try {
            if (facade != null) {
                entries.forEach { sourceEntry ->
                    val targetEntry = movedEntriesById[sourceEntry.id]
                        ?: throw IllegalStateException("MDBX target password is missing")
                    facade.relocateMdbxAttachments(sourceEntry, targetEntry)
                }
            }
        } catch (error: Throwable) {
            repository.updatePasswordEntries(entries)
            throw error
        }
        deleteMovedKeePassPasswordSources(entries, "mdbx")
        check(deleteMovedBitwardenPasswordSources(entries, "mdbx")) {
            "Bitwarden source delete could not be queued"
        }
    }

    suspend fun moveMdbxPasswordsToMonicaCategoryAwait(
        entries: List<PasswordEntry>,
        categoryId: Long?
    ) {
        if (entries.isEmpty()) return
        val now = Date()
        repository.updatePasswordEntries(
            entries.map { entry ->
                entry.copy(
                    categoryId = categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    mdbxDatabaseId = null,
                    mdbxFolderId = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    replicaGroupId = null,
                    isArchived = false,
                    archivedAt = null,
                    updatedAt = now
                )
            }
        )
        val facade = appContext?.let(AttachmentContainer::facade)
        try {
            if (facade != null) {
                entries.forEach { sourceEntry ->
                    facade.removeAttachmentsFromMdbxSource(sourceEntry)
                }
            }
        } catch (error: Throwable) {
            repository.updatePasswordEntries(entries)
            throw error
        }
    }


    private suspend fun movePasswordsToKeePassInternal(
        ids: List<Long>,
        buildUpdatedEntry: (PasswordEntry) -> PasswordEntry
    ) {
        val entries = repository.getPasswordsByIds(ids)
        entries.forEach { entry ->
            val updatedEntry = buildUpdatedEntry(entry)
            val customFields = resolveKeePassCustomFieldsForSync(
                entryId = entry.id,
                customFieldsOverride = null
            )
            val keepassSync = keepassPasswordUpdateExecutor.syncUpdatedEntry(
                existingEntry = entry,
                updatedEntry = updatedEntry,
                resolvePassword = { candidate ->
                    decodePasswordOrNull(candidate.password) ?: candidate.password
                },
                customFields = customFields,
                persistUpdate = { persistedEntry ->
                    repository.updatePasswordEntry(persistedEntry)
                }
            )
            if (keepassSync.isFailure) {
                Log.e(
                    "PasswordViewModel",
                    "KeePass password move failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                )
                return@forEach
            }

            if (entry.hasBitwardenCipherBinding()) {
                val vaultId = entry.bitwardenVaultId
                val cipherId = entry.bitwardenCipherId
                if (vaultId == null || cipherId.isNullOrBlank()) return@forEach

                val queueResult = bitwardenRepository?.queueCipherDelete(
                    vaultId = vaultId,
                    cipherId = cipherId,
                    entryId = entry.id
                ) ?: Result.failure(IllegalStateException(strings.get(R.string.entry_message_bitwarden_unavailable)))
                if (queueResult.isFailure) {
                    throw queueResult.exceptionOrNull()
                        ?: IllegalStateException(strings.get(R.string.bitwarden_message_delete_queue_failed))
                }
            }
            if (entry.mdbxDatabaseId != null) {
                appContext?.let { context ->
                    runCatching {
                        AttachmentContainer.facade(context).removeAttachmentsFromMdbxSource(entry)
                    }.onFailure { error ->
                        Log.e(
                            "PasswordViewModel",
                            "Failed to remove MDBX attachment mirrors after KeePass move: ${error.message}"
                        )
                    }
                }
            }
        }
    }

    fun movePasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) {
        viewModelScope.launch {
            movePasswordsToBitwardenFolderAwait(ids, vaultId, folderId)
        }
    }

    suspend fun movePasswordsToBitwardenFolderAwait(ids: List<Long>, vaultId: Long, folderId: String) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
        val now = Date()
        val targetEntries = entries.map { entry ->
            val keepExistingCipher = entry.bitwardenVaultId == vaultId &&
                !entry.bitwardenCipherId.isNullOrBlank()
            entry.copy(
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = null,
                mdbxFolderId = null,
                bitwardenVaultId = vaultId,
                bitwardenCipherId = entry.bitwardenCipherId.takeIf { keepExistingCipher },
                bitwardenFolderId = folderId,
                bitwardenRevisionDate = entry.bitwardenRevisionDate.takeIf { keepExistingCipher },
                bitwardenLocalModified = keepExistingCipher,
                replicaGroupId = null,
                isArchived = false,
                archivedAt = null,
                updatedAt = now
            )
        }
        repository.updatePasswordEntries(targetEntries)
        deleteMovedKeePassPasswordSources(entries, "bitwarden")
        bitwardenRepository?.requestLocalMutationSync(vaultId)
    }

    private suspend fun deleteMovedKeePassPasswordSources(
        entries: List<PasswordEntry>,
        target: String
    ): Boolean {
        val keepassEntries = entries.filter { it.keepassDatabaseId != null }
        if (keepassEntries.isEmpty()) return true
        val attachmentsReady = runCatching {
            materializeMovedKeePassAttachments(keepassEntries)
        }.onFailure { error ->
            Log.e(
                "PasswordViewModel",
                "KeePass source delete blocked after password move to $target because attachments are not local-safe: ${error.message}"
            )
        }.isSuccess
        if (!attachmentsReady) return false

        val deleted = keepassPasswordDeleteExecutor.deleteBatch(
            entries = keepassEntries,
            useRecycleBin = false
        )
        if (!deleted) {
            Log.e(
                "PasswordViewModel",
                "KeePass source delete failed after password move to $target; target data was kept"
            )
        }
        return deleted
    }

    private suspend fun deleteMovedBitwardenPasswordSources(
        entries: List<PasswordEntry>,
        target: String
    ): Boolean {
        val repositoryInstance = bitwardenRepository ?: return entries.none { it.hasBitwardenCipherBinding() }
        val sourceEntries = entries.filter(PasswordEntry::hasBitwardenCipherBinding)
        if (sourceEntries.isEmpty()) return true
        appContext?.let { context ->
            val attachmentRepository = AttachmentContainer.repository(context)
            sourceEntries.forEach { entry ->
                attachmentRepository.convertSourceToLocal(
                    passwordId = entry.id,
                    fromSource = AttachmentSource.BITWARDEN
                )
            }
        }
        val affectedVaultIds = linkedSetOf<Long>()
        sourceEntries.forEach { entry ->
            val vaultId = entry.bitwardenVaultId ?: return@forEach
            val cipherId = entry.bitwardenCipherId?.takeIf(String::isNotBlank) ?: return@forEach
            val result = repositoryInstance.queueCipherDelete(
                vaultId = vaultId,
                cipherId = cipherId,
                entryId = entry.id
            )
            if (result.isFailure) {
                Log.e(
                    "PasswordViewModel",
                    "Bitwarden source delete could not be queued after move to $target: ${result.exceptionOrNull()?.message}"
                )
                return false
            }
            affectedVaultIds += vaultId
        }
        affectedVaultIds.forEach(repositoryInstance::requestLocalMutationSync)
        return true
    }

    private suspend fun materializeMovedKeePassAttachments(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        val context = appContext ?: return
        val facade = AttachmentContainer.facade(context)
        val attachmentRepository = AttachmentContainer.repository(context)
        entries.forEach { entry ->
            val databaseId = entry.keepassDatabaseId ?: return@forEach
            val entryUuid = entry.keepassEntryUuid
            if (entryUuid.isNullOrBlank()) {
                val hasKeePassAttachments = attachmentRepository
                    .listByParentAndSource(entry.id, AttachmentSource.KEEPASS)
                    .isNotEmpty()
                if (hasKeePassAttachments) {
                    throw IllegalStateException("KeePass attachment transfer requires entry uuid")
                }
                return@forEach
            }
            facade.materializeKeePassAttachmentsForLocal(
                passwordId = entry.id,
                databaseId = databaseId,
                entryUuid = entryUuid
            )
        }
    }
    
    fun authenticate(password: String): Boolean {
        val isValid = securityManager.unlockVaultWithPassword(password)
        _isAuthenticated.value = isValid
        if (isValid) {
            securityManager.markVaultAuthenticated()
        }
        return isValid
    }

    /**
     * Restore only the UI-level authenticated flag.
     *
     * SessionManager and runtime unlock state must already be valid before this
     * is called. This method must not create or extend an unlock window.
     */
    fun restoreAuthenticatedUiState() {
        if (!_isAuthenticated.value) {
            _isAuthenticated.value = true
        }
    }

    /**
     * Backward-compatible wrapper for old call sites.
     */
    fun restoreAuthenticatedSession() {
        restoreAuthenticatedUiState()
    }

    /**
     * Developer bypass only affects UI state and must not mark the app session unlocked.
     */
    fun markAuthenticatedForBypass() {
        restoreAuthenticatedUiState()
    }
    
    fun setMasterPassword(password: String) {
        securityManager.setMasterPassword(password)
        _isAuthenticated.value = true
        securityManager.markVaultAuthenticated()
    }
    
    fun isMasterPasswordSet(): Boolean {
        return securityManager.isMasterPasswordSet()
    }
    
    fun logout() {
        _isAuthenticated.value = false
        SessionManager.markLocked()
    }
    
    fun addPasswordEntry(entry: PasswordEntry, onResult: (Long) -> Unit = {}) {
        addPasswordEntryWithResult(
            entry = entry,
            includeDetailedLog = true
        ) { id ->
            if (id != null) {
                onResult(id)
            }
        }
    }

    fun addPasswordEntryWithResult(
        entry: PasswordEntry,
        includeDetailedLog: Boolean = true,
        skipCategoryBinding: Boolean = false,
        passwordAlreadyEncrypted: Boolean = false,
        onResult: (Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                createPasswordEntryInternal(
                    entry = entry,
                    includeDetailedLog = includeDetailedLog,
                    skipCategoryBinding = skipCategoryBinding,
                    passwordAlreadyEncrypted = passwordAlreadyEncrypted
                )
            }
            onResult(id)
        }
    }

    private suspend fun createPasswordEntryInternal(
        entry: PasswordEntry,
        includeDetailedLog: Boolean,
        skipCategoryBinding: Boolean = false,
        passwordAlreadyEncrypted: Boolean = false,
        customFieldsOverride: List<CustomFieldDraft>? = null
    ): Long? {
        val boundEntry = (if (skipCategoryBinding) entry else applyCategoryBinding(entry)).let { candidate ->
            // 只在"纯 KeePass 新建"场景下补 entryUuid；若 candidate 同时绑定了 Bitwarden vault，
            // 说明 keepassDatabaseId 其实是 applyCategoryBinding 根据当前 UI 过滤误塞进去的，
            // 继续补 UUID 会让 entry 同时拥有 concrete KeePass + concrete Bitwarden 绑定，
            // resolveOwnership 会判为 Conflict 导致 normalizePasswordInsert 后续 block 整个创建。
            if (candidate.keepassDatabaseId != null &&
                candidate.keepassEntryUuid.isNullOrBlank() &&
                candidate.bitwardenVaultId == null
            ) {
                candidate.copy(keepassEntryUuid = UUID.randomUUID().toString())
            } else {
                candidate
            }
        }
        val normalizedBoundEntry = BitwardenMutationStateHelper.normalizePasswordInsert(boundEntry)
        if (normalizedBoundEntry.hasOwnershipConflict()) {
            Log.w(
                "PasswordViewModel",
                "Blocked password create because of ownership conflict"
            )
            return null
        }
        val encryptedEntry = normalizedBoundEntry.copy(
            // 复制已有条目（batch copy / cross-container）的 password 字段已经是 Monica SecurityManager
            // 加密过的密文，不需要再加密一次，否则解密时会多出一层导致显示乱码或用不了。
            password = if (passwordAlreadyEncrypted) {
                normalizedBoundEntry.password
            } else {
                securityManager.encryptData(normalizedBoundEntry.password)
            },
            authenticatorKey = encodeAuthenticatorKeyForStorage(normalizedBoundEntry.authenticatorKey),
            createdAt = Date(),
            updatedAt = Date()
        )
        val id = keepassPasswordCreateExecutor.create(
            localEntry = encryptedEntry,
            syncEntry = normalizedBoundEntry,
            insertEntry = repository::insertPasswordEntry,
            rollbackEntry = repository::deletePasswordEntryById,
            resolvePassword = { it.password },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = 0,
                customFieldsOverride = customFieldsOverride
            )
        ) ?: return null
        if (customFieldsOverride == null) {
            normalizedBoundEntry.bitwardenVaultId?.let { vaultId ->
                bitwardenRepository?.requestLocalMutationSync(vaultId)
            }
        }

        if (includeDetailedLog) {
            val createDetails = mutableListOf<takagi.ru.monica.utils.FieldChange>()
            if (normalizedBoundEntry.username.isNotBlank()) {
                createDetails.add(takagi.ru.monica.utils.FieldChange("用户名", "", normalizedBoundEntry.username))
            }
            if (normalizedBoundEntry.website.isNotBlank()) {
                createDetails.add(takagi.ru.monica.utils.FieldChange("网站", "", normalizedBoundEntry.website))
            }
            if (normalizedBoundEntry.password.isNotBlank()) {
                createDetails.add(takagi.ru.monica.utils.FieldChange("密码", "", "<redacted>"))
            }
            if (normalizedBoundEntry.notes.isNotBlank()) {
                createDetails.add(takagi.ru.monica.utils.FieldChange("备注", "", "<redacted>"))
            }
            takagi.ru.monica.utils.OperationLogger.logCreate(
                itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                itemId = id,
                itemTitle = normalizedBoundEntry.title,
                details = createDetails
            )
        }
        return id
    }

    suspend fun copyPasswordToMonicaLocal(
        entry: PasswordEntry,
        categoryId: Long?
    ): Long? {
        val newId = createPasswordEntryInternal(
            entry = buildMonicaLocalCopy(entry, categoryId),
            includeDetailedLog = false,
            skipCategoryBinding = true,
            // 源条目的 password 已经是 Monica SecurityManager 加密过的密文，直接复用
            passwordAlreadyEncrypted = true
        )
        if (newId != null) {
            copyCustomFieldsForEntryCopy(
                sourceEntryId = entry.id,
                targetEntryId = newId
            )
        }
        return newId
    }

    suspend fun rollbackPasswordTransferTargetAwait(entryId: Long) {
        val entry = repository.getPasswordEntryById(entryId) ?: return
        if (entry.keepassDatabaseId != null) {
            check(keepassPasswordDeleteExecutor.deleteBatch(
                entries = listOf(entry),
                useRecycleBin = false
            )) { "KeePass transfer target rollback failed" }
        }
        val vaultId = entry.bitwardenVaultId
        val cipherId = entry.bitwardenCipherId
        if (vaultId != null && !cipherId.isNullOrBlank()) {
            bitwardenRepository?.queueCipherDelete(
                vaultId = vaultId,
                cipherId = cipherId,
                entryId = entry.id
            )?.getOrThrow()
        }
        appContext?.let { context ->
            runCatching { AttachmentContainer.facade(context).purgeByPassword(entryId) }
        }
        repository.deletePasswordEntryById(entryId)
        vaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
    }

    suspend fun moveBitwardenPasswordToMonicaLocal(
        entry: PasswordEntry,
        categoryId: Long?
    ): Result<Long> {
        val newId = copyPasswordToMonicaLocal(entry, categoryId)
            ?: return Result.failure(IllegalStateException(strings.get(R.string.entry_message_local_copy_failed)))

        val facade = appContext?.let(AttachmentContainer::facade)
        if (facade != null) {
            val attachmentCount = facade.listByPassword(entry.id).size
            if (attachmentCount > 0) {
                val copiedCount = runCatching {
                    facade.cloneAttachmentsToNewParent(entry.id, newId)
                }.getOrElse { error ->
                    runCatching { facade.purgeByPassword(newId) }
                    repository.deletePasswordEntryById(newId)
                    return Result.failure(error)
                }
                if (copiedCount != attachmentCount) {
                    runCatching { facade.purgeByPassword(newId) }
                    repository.deletePasswordEntryById(newId)
                    return Result.failure(IllegalStateException(strings.get(R.string.entry_message_attachments_incomplete)))
                }
            }
        }

        val vaultId = entry.bitwardenVaultId
        val cipherId = entry.bitwardenCipherId
        if (vaultId != null && !cipherId.isNullOrBlank()) {
            val queueResult = bitwardenRepository?.queueCipherDelete(
                vaultId = vaultId,
                cipherId = cipherId,
                entryId = entry.id
            ) ?: Result.failure(IllegalStateException(strings.get(R.string.entry_message_bitwarden_unavailable)))
            if (queueResult.isFailure) {
                if (facade != null) runCatching { facade.purgeByPassword(newId) }
                repository.deletePasswordEntryById(newId)
                return Result.failure(
                    queueResult.exceptionOrNull() ?: IllegalStateException(strings.get(R.string.bitwarden_message_delete_queue_failed))
                )
            }
        }

        if (facade != null) {
            runCatching { facade.purgeByPassword(entry.id) }
                .onFailure { error ->
                    Log.w(
                        "PasswordViewModel",
                        "Bitwarden source attachment cache cleanup failed after a safe clone: ${error.message}"
                    )
                }
        }
        repository.deletePasswordEntry(entry)
        repository.deleteArchiveSyncMeta(entry.id)
        return Result.success(newId)
    }

    private fun buildMonicaLocalCopy(
        entry: PasswordEntry,
        categoryId: Long?
    ): PasswordEntry {
        return entry.copy(
            id = 0,
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )
    }

    fun addSecureItem(item: SecureItem) {
        viewModelScope.launch {
            secureItemRepository?.insertItem(item)
        }
    }
    
    /**
     * 快速添加密码（从底部导航栏快速添加）
     */
    fun quickAddPassword(title: String, username: String, password: String) {
        if (title.isBlank()) return
        val entry = PasswordEntry(
            title = title,
            username = username,
            password = password,
            website = "",
            notes = "",
            isFavorite = false
        )
        addPasswordEntry(entry)
    }
    
    fun updatePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            updatePasswordEntryInternal(entry)
        }
    }

    fun updateBoundNoteId(id: Long, noteId: Long?) {
        viewModelScope.launch {
            repository.getPasswordEntryById(id)?.let { entry ->
                val resolvedNoteId = if (noteId != null && entry.mdbxDatabaseId != null) {
                    val note = secureItemRepository?.getItemById(noteId)
                    if (note != null && note.itemType == ItemType.NOTE) {
                        secureItemRepository.ensureMdbxCopyForBinding(
                            source = note,
                            databaseId = entry.mdbxDatabaseId
                        ).id
                    } else {
                        noteId
                    }
                } else {
                    noteId
                }
                updatePasswordEntryInternal(entry.copy(boundNoteId = resolvedNoteId))
            }
        }
    }

    private suspend fun updatePasswordEntryInternal(
        entry: PasswordEntry,
        customFieldsOverride: List<CustomFieldDraft>? = null
    ): Boolean {
        // 获取旧数据用于对比
        val oldEntry = repository.getPasswordEntryById(entry.id)
        
        // 应用分类绑定
        val boundEntry = applyCategoryBinding(entry)
        if (boundEntry.hasOwnershipConflict()) {
            Log.w(
                "PasswordViewModel",
                "Blocked password update because of ownership conflict: entryId=${boundEntry.id}"
            )
            return false
        }
        val entryToUpdate = if (boundEntry.bitwardenVaultId != null) {
            boundEntry.copy(bitwardenLocalModified = true)
        } else {
            boundEntry
        }
        
        val oldPassword = oldEntry?.let { decryptForDisplay(it.password) } ?: ""
        val resolvedPassword = resolvePasswordForUpdate(
            existing = oldEntry,
            pendingEntry = entryToUpdate,
            incomingPassword = entryToUpdate.password
        )
        val newPassword = decryptForDisplay(resolvedPassword)
        val persistedEntry = entryToUpdate.copy(
            password = resolvedPassword,
            authenticatorKey = encodeAuthenticatorKeyForStorage(entryToUpdate.authenticatorKey),
            updatedAt = Date()
        )

        val keepassSync = keepassPasswordUpdateExecutor.syncUpdatedEntry(
            existingEntry = oldEntry,
            updatedEntry = persistedEntry,
            resolvePassword = { entryToUpdate.password },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = entryToUpdate.id,
                customFieldsOverride = customFieldsOverride
            ),
            persistUpdate = { updated ->
                repository.updatePasswordEntry(updated)
            }
        )
        if (keepassSync.isFailure) {
            Log.e(
                "PasswordViewModel",
                "KeePass password update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
            )
            return false
        }

        if (oldEntry != null && oldPassword.isNotBlank() && oldPassword != newPassword) {
            savePasswordHistorySnapshot(entryToUpdate.id, oldPassword)
        }

        if (customFieldsOverride == null) {
            entryToUpdate.bitwardenVaultId?.let { vaultId ->
                bitwardenRepository?.requestLocalMutationSync(vaultId)
            }
        }
        
        // 记录更新操作
        val changes = takagi.ru.monica.utils.OperationLogger.compareAndGetChanges(
            old = oldEntry,
            new = entryToUpdate,
            fields = listOf(
                "标题" to { it.title },
                "用户名" to { it.username },
                "网站" to { it.website },
                "备注" to { it.notes }
            )
        )

        // 原始值只交给加密版本快照；OperationLogger 写入审计日志前仍会脱敏。
        if (oldEntry != null && oldPassword != newPassword) {
            val updatedChanges = changes.toMutableList()
            updatedChanges.add(
                takagi.ru.monica.utils.FieldChange(
                    fieldName = "密码",
                    oldValue = oldPassword,
                    newValue = newPassword
                )
            )
            takagi.ru.monica.utils.OperationLogger.logUpdate(
                itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                itemId = entryToUpdate.id,
                itemTitle = entryToUpdate.title,
                changes = updatedChanges
            )
            return true
        }
        takagi.ru.monica.utils.OperationLogger.logUpdate(
            itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
            itemId = entryToUpdate.id,
            itemTitle = entryToUpdate.title,
            changes = changes
        )
        return true
    }

    private suspend fun savePasswordHistorySnapshot(entryId: Long, plainPassword: String) {
        if (plainPassword.isBlank()) return

        val latestHistory = repository.getPasswordHistoryByEntryIdSync(entryId).firstOrNull()
        val latestPassword = latestHistory?.let { decryptForDisplay(it.password) }
        if (latestPassword == plainPassword) return

        repository.insertPasswordHistory(
            PasswordHistoryEntry(
                entryId = entryId,
                password = securityManager.encryptDataLegacyCompat(plainPassword),
                lastUsedAt = Date()
            )
        )
        repository.trimPasswordHistory(entryId, PASSWORD_HISTORY_LIMIT)
    }
    
    fun deletePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            val trashEnabled = trashSettings?.value?.first ?: true
            val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
            val keepassId = entry.keepassDatabaseId
            val bitwardenVaultId = entry.bitwardenVaultId
            val bitwardenCipherId = entry.bitwardenCipherId
            val isBitwardenCipher = bitwardenVaultId != null && commandPolicy.usesRemoteDeleteQueue
            Log.i(
                "PasswordViewModel",
                "Delete requested: id=${entry.id}, title=${entry.title}, keepassId=$keepassId, trashEnabled=$trashEnabled, bitwardenCipher=$isBitwardenCipher"
            )

            if (isBitwardenCipher) {
                handleBitwardenQueuedDelete(
                    entry = entry,
                    vaultId = bitwardenVaultId!!,
                    cipherId = bitwardenCipherId!!,
                    commandPolicy = commandPolicy
                )
                return@launch
            }

            if (keepassId != null && !canWriteKeePassDatabase(keepassId)) {
                _keepassPermissionRepairRequests.tryEmit(
                    KeePassPermissionRepairRequest(databaseId = keepassId, entry = entry)
                )
                if (_keepassPermissionRepairRequests.subscriptionCount.value == 0) {
                    showKeePassWritePermissionError()
                }
                return@launch
            }
              
            if (trashEnabled) {
                moveEntryToTrash(
                    entry = entry,
                    keepassId = keepassId,
                    commandPolicy = commandPolicy
                )
            } else {
                permanentlyDeleteEntry(entry)
            }
        }
    }

    suspend fun deletePasswordEntriesBatch(
        entries: List<PasswordEntry>,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null
    ): Int {
        if (entries.isEmpty()) return 0

        val trashEnabled = trashSettings?.value?.first ?: true
        var deletedCount = 0
        var processedCount = 0
        val totalCount = entries.size
        onProgress?.invoke(processedCount, totalCount)
        val keepassTargets = mutableListOf<
            Pair<PasswordEntry, takagi.ru.monica.domain.provider.PasswordCommandPolicy>
        >()
        val localTargets = mutableListOf<
            Pair<PasswordEntry, takagi.ru.monica.domain.provider.PasswordCommandPolicy>
        >()

        entries.forEach { entry ->
            val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
            val bitwardenVaultId = entry.bitwardenVaultId
            val bitwardenCipherId = entry.bitwardenCipherId
            val isBitwardenCipher = bitwardenVaultId != null && commandPolicy.usesRemoteDeleteQueue

            if (entry.keepassDatabaseId != null && !isBitwardenCipher) {
                keepassTargets += entry to commandPolicy
            } else {
                val deleted = if (isBitwardenCipher) {
                    if (!bitwardenCipherId.isNullOrBlank()) {
                        handleBitwardenQueuedDelete(
                            entry = entry,
                            vaultId = bitwardenVaultId!!,
                            cipherId = bitwardenCipherId,
                            commandPolicy = commandPolicy
                        )
                    } else {
                        false
                    }
                } else {
                    localTargets += entry to commandPolicy
                    true
                }
                if (deleted && isBitwardenCipher) {
                    deletedCount++
                    processedCount++
                    onProgress?.invoke(processedCount, totalCount)
                } else if (!isBitwardenCipher) {
                    // Local deletes are flushed below through repository batch APIs so MDBX gets one commit per vault.
                } else {
                    processedCount++
                    onProgress?.invoke(processedCount, totalCount)
                }
            }
        }

        if (localTargets.isNotEmpty()) {
            val appliedCount = applyLocalDeleteBatch(localTargets, trashEnabled)
            deletedCount += appliedCount
            repeat(localTargets.size) {
                processedCount++
                onProgress?.invoke(processedCount, totalCount)
            }
        }

        if (keepassTargets.isEmpty()) return deletedCount

        keepassTargets
            .groupBy { it.first.keepassDatabaseId }
            .values
            .forEach { groupedEntries ->
                groupedEntries
                    .chunked(KEEPASS_BATCH_DELETE_CHUNK_SIZE)
                    .forEach { chunk ->
                        val chunkEntries = chunk.map { it.first }
                        val remoteDeleted = keepassPasswordDeleteExecutor.deleteBatch(
                            entries = chunkEntries,
                            useRecycleBin = trashEnabled
                        )
                        if (!remoteDeleted) {
                            showKeePassWritePermissionError()
                            Log.e(
                                "PasswordViewModel",
                                "KeePass batch delete failed: trash=$trashEnabled, ids=${chunkEntries.map { it.id }}"
                            )
                            // 批量路径失败时退回逐条删除，尽可能提升成功率并输出真实进度。
                            val singleDeletedTargets = mutableListOf<
                                Pair<PasswordEntry, takagi.ru.monica.domain.provider.PasswordCommandPolicy>
                            >()
                            chunk.forEach { (entry, commandPolicy) ->
                                val singleDeleted = keepassPasswordDeleteExecutor.delete(
                                    entry = entry,
                                    useRecycleBin = trashEnabled
                                )
                                if (singleDeleted) {
                                    singleDeletedTargets += entry to commandPolicy
                                }
                            }
                            if (singleDeletedTargets.isNotEmpty()) {
                                deletedCount += applyLocalDeleteBatch(singleDeletedTargets, trashEnabled)
                            }
                            repeat(chunk.size) {
                                processedCount++
                                onProgress?.invoke(processedCount, totalCount)
                            }
                            return@forEach
                        }

                        deletedCount += applyLocalDeleteBatch(chunk, trashEnabled)
                        repeat(chunk.size) {
                            processedCount++
                            onProgress?.invoke(processedCount, totalCount)
                        }
                    }
            }

        return deletedCount
    }

    private suspend fun handleBitwardenQueuedDelete(
        entry: PasswordEntry,
        vaultId: Long,
        cipherId: String,
        commandPolicy: takagi.ru.monica.domain.provider.PasswordCommandPolicy
    ): Boolean {
        val queueResult = bitwardenRepository?.queueCipherDelete(
            vaultId = vaultId,
            cipherId = cipherId,
            entryId = entry.id
        )
        if (queueResult?.isFailure == true) {
            Log.e(
                "PasswordViewModel",
                "Queue Bitwarden delete failed: ${queueResult.exceptionOrNull()?.message}"
            )
            return false
        }
        if (!keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = true)) return false

        val tombstone = passwordCommandStateFactory.createQueuedDeleteTombstone(
            entry = entry,
            now = Date(),
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(tombstone)
        repository.deleteArchiveSyncMeta(entry.id)
        takagi.ru.monica.utils.OperationLogger.logDelete(
            itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title,
            detail = "移入回收站（待同步删除）"
        )
        Log.i("PasswordViewModel", "Delete queued as tombstone: id=${entry.id}")
        return true
    }

    private suspend fun applyLocalDeleteBatch(
        entries: List<Pair<PasswordEntry, takagi.ru.monica.domain.provider.PasswordCommandPolicy>>,
        trashEnabled: Boolean
    ): Int {
        if (entries.isEmpty()) return 0
        val originalEntries = entries.map { it.first }
        if (trashEnabled) {
            val now = Date()
            val softDeletedEntries = entries.map { (entry, commandPolicy) ->
                passwordCommandStateFactory.createSoftDeletedEntry(
                    entry = entry,
                    now = now,
                    commandPolicy = commandPolicy
                )
            }
            repository.updatePasswordEntries(softDeletedEntries)
            repository.deleteArchiveSyncMeta(originalEntries.map { it.id })
            originalEntries.forEach { entry ->
                takagi.ru.monica.utils.OperationLogger.logDelete(
                    itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                    itemId = entry.id,
                    itemTitle = entry.title,
                    detail = "移入回收站"
                )
                Log.i("PasswordViewModel", "Delete moved to trash: id=${entry.id}")
            }
        } else {
            repository.deletePasswordEntries(originalEntries)
            repository.deleteArchiveSyncMeta(originalEntries.map { it.id })
            originalEntries.forEach { entry ->
                takagi.ru.monica.utils.OperationLogger.logDelete(
                    itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                    itemId = entry.id,
                    itemTitle = entry.title
                )
                Log.i("PasswordViewModel", "Delete permanently removed: id=${entry.id}")
            }
        }
        return originalEntries.size
    }

    private suspend fun moveEntryToTrash(
        entry: PasswordEntry,
        keepassId: Long?,
        commandPolicy: takagi.ru.monica.domain.provider.PasswordCommandPolicy
    ) {
        moveEntryToTrashLocalOnly(entry, commandPolicy)

        if (keepassId != null) {
            syncKeePassTrashDelete(entry)
        }
    }

    private suspend fun moveEntryToTrashLocalOnly(
        entry: PasswordEntry,
        commandPolicy: takagi.ru.monica.domain.provider.PasswordCommandPolicy
    ) {
        val softDeletedEntry = passwordCommandStateFactory.createSoftDeletedEntry(
            entry = entry,
            now = Date(),
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(softDeletedEntry)
        repository.deleteArchiveSyncMeta(entry.id)
        takagi.ru.monica.utils.OperationLogger.logDelete(
            itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title,
            detail = "移入回收站"
        )
        Log.i("PasswordViewModel", "Delete moved to trash: id=${entry.id}")
    }

    private fun syncKeePassTrashDelete(entry: PasswordEntry) {
        viewModelScope.launch keepassDeleteSync@{
            if (keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = true)) {
                Log.i("PasswordViewModel", "KeePass trash delete synced: id=${entry.id}")
                return@keepassDeleteSync
            }

            Log.e("PasswordViewModel", "KeePass trash delete failed, reverting local trash state: id=${entry.id}")
            showKeePassWritePermissionError()
            repository.updatePasswordEntry(
                passwordCommandStateFactory.createTrashRevertedEntry(
                    entry = entry,
                    now = Date()
                )
            )
        }
    }

    private suspend fun permanentlyDeleteEntry(entry: PasswordEntry) {
        if (!keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = false)) {
            showKeePassWritePermissionError()
            return
        }

        permanentlyDeleteEntryLocalOnly(entry)
    }

    private suspend fun permanentlyDeleteEntryLocalOnly(entry: PasswordEntry) {
        repository.deletePasswordEntry(entry)
        repository.deleteArchiveSyncMeta(entry.id)
        takagi.ru.monica.utils.OperationLogger.logDelete(
            itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title
        )
        Log.i("PasswordViewModel", "Delete permanently removed: id=${entry.id}")
    }
    
    fun toggleFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(id, isFavorite)
        }
    }

    fun archivePassword(id: Long) {
        viewModelScope.launch {
            archivePasswordsInternal(listOf(id))
        }
    }

    fun archivePasswords(ids: List<Long>) {
        viewModelScope.launch {
            archivePasswordsInternal(ids)
        }
    }

    fun unarchivePassword(id: Long) {
        viewModelScope.launch {
            unarchivePasswordsInternal(listOf(id))
        }
    }

    fun unarchivePasswords(ids: List<Long>) {
        viewModelScope.launch {
            unarchivePasswordsAwait(ids)
        }
    }

    suspend fun unarchivePasswordsAwait(ids: List<Long>) {
        unarchivePasswordsInternal(ids)
    }

    private suspend fun archivePasswordsInternal(ids: List<Long>) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
            .filter { !it.isDeleted }
        entries.forEach { entry ->
            archiveSingleEntry(entry)
        }
    }

    private suspend fun unarchivePasswordsInternal(ids: List<Long>) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
            .filter { !it.isDeleted }
        entries.forEach { entry ->
            unarchiveSingleEntry(entry)
        }
    }

    private suspend fun archiveSingleEntry(entry: PasswordEntry) {
        if (entry.isArchived || entry.isDeleted) return

        val now = Date()
        val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
        val providerType = commandPolicy.archiveProviderType
        val keepassDatabaseId = entry.keepassDatabaseId

        var archivedEntry = passwordCommandStateFactory.createArchivedEntry(
            entry = entry,
            now = now,
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(archivedEntry)

        val archiveResult = archiveEntryByProvider(
            entry = archivedEntry,
            keepassDatabaseId = keepassDatabaseId,
            providerType = providerType
        )
        archivedEntry = archiveResult.entry

        repository.upsertArchiveSyncMeta(buildArchiveSyncMeta(
            entry = entry,
            providerType = providerType,
            keepassDatabaseId = keepassDatabaseId,
            syncStatus = archiveResult.syncStatus,
            lastError = archiveResult.lastError
        ))
    }

    private suspend fun unarchiveSingleEntry(entry: PasswordEntry) {
        if (!entry.isArchived || entry.isDeleted) return

        val now = Date()
        val archiveMeta = repository.getArchiveSyncMeta(entry.id)
        val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
        val providerType = archiveMeta?.providerType ?: commandPolicy.archiveProviderType
        val keepassDatabaseId = entry.keepassDatabaseId

        var unarchivedEntry = passwordCommandStateFactory.createUnarchivedEntry(
            entry = entry,
            now = now,
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(unarchivedEntry)

        val unarchiveResult = unarchiveEntryByProvider(
            entry = unarchivedEntry,
            archiveMeta = archiveMeta,
            keepassDatabaseId = keepassDatabaseId,
            providerType = providerType
        )
        unarchivedEntry = unarchiveResult.entry

        repository.upsertArchiveSyncMeta(buildUnarchiveSyncMeta(
            entry = entry,
            archiveMeta = archiveMeta,
            providerType = providerType,
            keepassDatabaseId = keepassDatabaseId,
            syncStatus = unarchiveResult.syncStatus,
            lastError = unarchiveResult.lastError
        ))
    }

    private data class ArchiveOperationResult(
        val entry: PasswordEntry,
        val syncStatus: String,
        val lastError: String?
    )

    private suspend fun archiveEntryByProvider(
        entry: PasswordEntry,
        keepassDatabaseId: Long?,
        providerType: String
    ): ArchiveOperationResult {
        if (providerType != PasswordArchiveSyncMeta.PROVIDER_KEEPASS_GROUP) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = defaultArchiveSyncStatus(providerType),
                lastError = null
            )
        }

        val targetArchivePath = ensureKeePassArchiveGroupPath(keepassDatabaseId)
        if (targetArchivePath == null) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = "KeePass archive group unavailable"
            )
        }
        val moveResult = moveKeePassEntryGroupPath(entry = entry, targetGroupPath = targetArchivePath)
        if (moveResult.isFailure) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = moveResult.exceptionOrNull()?.message ?: "KeePass archive move failed"
            )
        }

        val archivedEntry = entry.copy(keepassGroupPath = targetArchivePath, updatedAt = Date())
        repository.updatePasswordEntry(archivedEntry)
        return ArchiveOperationResult(
            entry = archivedEntry,
            syncStatus = PasswordArchiveSyncMeta.STATUS_SYNCED,
            lastError = null
        )
    }

    private suspend fun unarchiveEntryByProvider(
        entry: PasswordEntry,
        archiveMeta: PasswordArchiveSyncMeta?,
        keepassDatabaseId: Long?,
        providerType: String
    ): ArchiveOperationResult {
        if (providerType != PasswordArchiveSyncMeta.PROVIDER_KEEPASS_GROUP) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = defaultArchiveSyncStatus(providerType),
                lastError = null
            )
        }

        val preferredPath = archiveMeta?.originKeePassGroupPath
        val restorePath = resolveKeePassRestorePathOrRoot(keepassDatabaseId, preferredPath)
        val moveResult = moveKeePassEntryGroupPath(entry = entry, targetGroupPath = restorePath)
        if (moveResult.isFailure) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = moveResult.exceptionOrNull()?.message ?: "KeePass unarchive move failed"
            )
        }

        val restoredEntry = entry.copy(keepassGroupPath = restorePath, updatedAt = Date())
        repository.updatePasswordEntry(restoredEntry)
        val lastError = if (preferredPath != null && preferredPath != restorePath) {
            "Origin group missing, restored to root"
        } else {
            null
        }
        return ArchiveOperationResult(
            entry = restoredEntry,
            syncStatus = PasswordArchiveSyncMeta.STATUS_SYNCED,
            lastError = lastError
        )
    }

    private fun defaultArchiveSyncStatus(providerType: String): String {
        return if (providerType == PasswordArchiveSyncMeta.PROVIDER_LOCAL) {
            PasswordArchiveSyncMeta.STATUS_SYNCED
        } else {
            PasswordArchiveSyncMeta.STATUS_PENDING
        }
    }

    private fun buildArchiveSyncMeta(
        entry: PasswordEntry,
        providerType: String,
        keepassDatabaseId: Long?,
        syncStatus: String,
        lastError: String?
    ): PasswordArchiveSyncMeta {
        return PasswordArchiveSyncMeta(
            entryId = entry.id,
            providerType = providerType,
            originKeePassDatabaseId = keepassDatabaseId,
            originKeePassGroupPath = entry.keepassGroupPath,
            originBitwardenFolderId = entry.bitwardenFolderId,
            syncStatus = syncStatus,
            lastError = lastError,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun buildUnarchiveSyncMeta(
        entry: PasswordEntry,
        archiveMeta: PasswordArchiveSyncMeta?,
        providerType: String,
        keepassDatabaseId: Long?,
        syncStatus: String,
        lastError: String?
    ): PasswordArchiveSyncMeta {
        return PasswordArchiveSyncMeta(
            entryId = entry.id,
            providerType = providerType,
            originKeePassDatabaseId = archiveMeta?.originKeePassDatabaseId ?: keepassDatabaseId,
            originKeePassGroupPath = archiveMeta?.originKeePassGroupPath,
            originBitwardenFolderId = archiveMeta?.originBitwardenFolderId ?: entry.bitwardenFolderId,
            syncStatus = syncStatus,
            lastError = lastError,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun moveKeePassEntryGroupPath(
        entry: PasswordEntry,
        targetGroupPath: String?
    ): Result<Unit> {
        val databaseId = entry.keepassDatabaseId
            ?: return Result.failure(IllegalStateException("No KeePass database bound"))
        val bridge = keepassBridge
            ?: return Result.failure(IllegalStateException("KeePass bridge unavailable"))

        return bridge.updateLegacyPasswordEntry(
            databaseId = databaseId,
            entry = entry.copy(keepassGroupPath = targetGroupPath),
            resolvePassword = { resolvePlainPasswordForKeePass(it.password) },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = entry.id,
                customFieldsOverride = null
            )
        )
    }

    private suspend fun ensureKeePassArchiveGroupPath(databaseId: Long?): String? {
        val resolvedDatabaseId = databaseId ?: return null
        val bridge = keepassBridge ?: return null

        val rootPath = buildKeePassPathKey(null, MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME)
        val archivePath = buildKeePassPathKey(rootPath, MONICA_KEEPASS_ARCHIVE_GROUP_NAME)

        var groups = bridge.listLegacyGroups(resolvedDatabaseId).getOrElse { return null }

        if (groups.none { it.path == rootPath }) {
            val rootResult = bridge.createLegacyGroup(
                databaseId = resolvedDatabaseId,
                groupName = MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME
            )
            if (rootResult.isFailure) return null
            groups = bridge.listLegacyGroups(resolvedDatabaseId).getOrElse { return null }
        }

        if (groups.none { it.path == archivePath }) {
            val archiveResult = bridge.createLegacyGroup(
                databaseId = resolvedDatabaseId,
                groupName = MONICA_KEEPASS_ARCHIVE_GROUP_NAME,
                parentPath = rootPath
            )
            if (archiveResult.isFailure) return null
        }

        return archivePath
    }

    private suspend fun resolveKeePassRestorePathOrRoot(
        databaseId: Long?,
        preferredPath: String?
    ): String? {
        if (databaseId == null) return preferredPath
        if (preferredPath.isNullOrBlank()) return null
        val bridge = keepassBridge ?: return null

        val groups = bridge.listLegacyGroups(databaseId).getOrNull() ?: return null
        return groups.firstOrNull { it.path == preferredPath }?.path
    }

    private fun resolvePlainPasswordForKeePass(storedPassword: String): String {
        if (storedPassword.isBlank()) return ""
        return try {
            decryptForDisplay(storedPassword)
        } catch (_: Exception) {
            storedPassword
        }
    }
    
    fun toggleGroupCover(id: Long, website: String, isGroupCover: Boolean) {
        viewModelScope.launch {
            if (isGroupCover) {
                // 设置为封面,会自动清除该分组的其他封面
                repository.setGroupCover(id, website)
            } else {
                // 取消封面
                repository.updateGroupCoverStatus(id, false)
            }
        }
    }
    
    fun updateSortOrders(items: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateSortOrders(items)
        }
    }

    /**
     * 更新绑定的验证器密钥
     */
    fun updateAuthenticatorKey(id: Long, authenticatorKey: String) {
        viewModelScope.launch {
            repository.updateAuthenticatorKey(id, encodeAuthenticatorKeyForStorage(authenticatorKey))
        }
    }

    /**
     * 更新绑定的通行密钥元数据
     */
    fun updatePasskeyBindings(id: Long, passkeyBindings: String) {
        viewModelScope.launch {
            repository.updatePasskeyBindings(id, passkeyBindings)
        }
    }
    
    suspend fun getPasswordEntryById(id: Long): PasswordEntry? {
        return getRawPasswordEntryById(id)?.let { entry ->
            entry.copy(password = inspectSecretState(entry).plainValueOrEmpty())
        }
    }

    suspend fun recoverUnreadableBitwardenEntry(entryId: Long): BitwardenRecoveryResult {
        val entry = repository.getPasswordEntryById(entryId)
            ?: return BitwardenRecoveryResult.Error("Entry not found")
        val vaultId = entry.bitwardenVaultId
            ?: return BitwardenRecoveryResult.Error("Entry is not backed by Bitwarden")
        if (entry.bitwardenCipherId.isNullOrBlank()) {
            return BitwardenRecoveryResult.Error("Entry has no Bitwarden cipher binding")
        }
        val repositoryInstance = bitwardenRepository
            ?: return BitwardenRecoveryResult.Error("Bitwarden repository unavailable")

        return when (val result = repositoryInstance.syncForUserVisibleRequest(
            vaultId = vaultId,
            requestIdPrefix = "bw-password-recover-vault"
        )) {
            is BitwardenRepository.SyncResult.Success -> BitwardenRecoveryResult.Success
            is BitwardenRepository.SyncResult.Error -> BitwardenRecoveryResult.Error(result.message)
            is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                BitwardenRecoveryResult.EmptyVaultBlocked(result.reason)
            }
        }
    }

    fun getPasswordHistoryFlow(passwordId: Long): Flow<List<PasswordHistoryEntry>> {
        return repository.getPasswordHistoryByEntryId(passwordId)
            .map { entries ->
                entries.mapNotNull { entry ->
                    val decoded = decodeHistoryPasswordForDisplay(entry)
                    if (decoded.isBlank()) {
                        null
                    } else {
                        entry.copy(password = decoded)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * 为附件下载提供 [takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext]。
     *
     * 返回 null 表示 vault 未解锁或会话无效。
     */
    fun getAttachmentBitwardenContext(
        vault: BitwardenVault,
        cipherId: String?
    ): takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext? {
        return bitwardenRepository?.getAttachmentBitwardenContext(vault, cipherId)
    }

    fun getBitwardenSyncRawHistoryFlow(
        vaultId: Long,
        cipherId: String
    ): Flow<List<BitwardenSyncRawHistoryItem>> {
        if (cipherId.isBlank()) return flowOf(emptyList())
        return repository.getBitwardenSyncRawRecords(vaultId, cipherId)
            .map { entries ->
                entries.map { entry ->
                    val payload = decodePasswordOrNull(entry.payloadCipherText)
                    BitwardenSyncRawHistoryItem(
                        id = entry.id,
                        operation = entry.operation,
                        endpoint = entry.endpoint,
                        payloadSource = entry.payloadSource,
                        payloadDigest = entry.payloadDigest,
                        responseCode = entry.responseCode,
                        success = entry.success,
                        capturedAt = entry.capturedAt,
                        payload = payload,
                        preview = bitwardenSnapshotPreviewParser.parse(
                            payload = payload,
                            symmetricKey = bitwardenRepository?.getCachedSymmetricKey(vaultId)
                        )
                    )
                }.filter { it.payloadSource == "SYNC_RESPONSE" }
            }
            .flowOn(Dispatchers.IO)
    }

    fun deletePasswordHistoryEntry(historyId: Long) {
        viewModelScope.launch {
            repository.deletePasswordHistoryById(historyId)
        }
    }

    fun clearPasswordHistory(entryId: Long) {
        viewModelScope.launch {
            repository.clearPasswordHistory(entryId)
        }
    }

    /**
     * Get linked TOTP data for a password entry
     */
    fun getLinkedTotpFlow(passwordId: Long): Flow<TotpData?> {
        val itemFlow = secureItemRepository?.getItemsByType(ItemType.TOTP) ?: return flowOf(null)
        return combine(itemFlow, repository.getAllPasswordEntries()) { items, passwords ->
            val boundPassword = passwords.firstOrNull { it.id == passwordId }
            val candidates = items.mapNotNull { item ->
                val data = parseStoredTotpData(item)
                if (data?.boundPasswordId == passwordId) item to data else null
            }
            val preferred = if (boundPassword?.mdbxDatabaseId != null) {
                candidates.firstOrNull { (item, _) ->
                    item.mdbxDatabaseId == boundPassword.mdbxDatabaseId
                }
            } else {
                candidates.firstOrNull { (item, _) ->
                    item.mdbxDatabaseId == null
                }
            } ?: candidates.firstOrNull()
            preferred?.second
        }.flowOn(Dispatchers.Default)
    }

    private fun normalizeMdbxTransferParent(parentFolderId: String?): String =
        parentFolderId?.takeIf { it.isNotBlank() } ?: "root"

    internal suspend fun copySecureItemsToMdbxBatch(
        items: List<SecureItem>,
        databaseId: Long,
        folderId: String?
    ): MdbxSecureItemBatchResult {
        val secureRepository = secureItemRepository
            ?: return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        if (items.isEmpty()) return MdbxSecureItemBatchResult(0, 0)

        val prepared = items.mapNotNull { source ->
            val storedItemData = prepareSecureItemDataForMdbx(source, isCopy = true)
                ?: return@mapNotNull null
            source to source.asMdbxBatchCopy(
                databaseId = databaseId,
                folderId = folderId,
                storedItemData = storedItemData
            )
        }
        if (prepared.isEmpty()) {
            return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }

        return runCatching {
            val createdIds = secureRepository.insertItems(prepared.map { it.second })
            check(createdIds.size == prepared.size) { "MDBX secure item copy returned an incomplete ID list" }
            prepared.zip(createdIds).forEach { (pair, createdId) ->
                val source = pair.first
                source.itemType.toOperationLogItemTypeOrNull()?.let { operationType ->
                    takagi.ru.monica.utils.OperationLogger.logCreate(
                        itemType = operationType,
                        itemId = createdId,
                        itemTitle = source.title
                    )
                }
            }
            MdbxSecureItemBatchResult(
                successCount = prepared.size,
                failedCount = items.size - prepared.size,
                createdIds = createdIds
            )
        }.getOrElse { error ->
            Log.e("PasswordViewModel", "MDBX secure item batch copy failed", error)
            MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }
    }

    internal suspend fun moveSecureItemsToMdbxBatch(
        items: List<SecureItem>,
        databaseId: Long,
        folderId: String?
    ): MdbxSecureItemBatchResult {
        val secureRepository = secureItemRepository
            ?: return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        if (items.isEmpty()) return MdbxSecureItemBatchResult(0, 0)

        val conflictIds = findMdbxReplicaTargetConflictIds(
            selectedItems = items,
            activeItems = secureRepository.getAllItems().first(),
            databaseId = databaseId,
            folderId = folderId
        )
        val prepared = mutableListOf<SecureItem>()
        val queuedBitwardenVaultIds = linkedSetOf<Long>()
        items.forEach { source ->
            if (source.id in conflictIds) return@forEach
            when (val ownership = source.resolveOwnership()) {
                SecureItemOwnership.MonicaLocal,
                is SecureItemOwnership.Mdbx -> Unit

                is SecureItemOwnership.Bitwarden -> {
                    val vaultId = ownership.vaultId ?: return@forEach
                    val cipherId = ownership.cipherId?.takeIf(String::isNotBlank) ?: return@forEach
                    val pendingItemType = source.itemType.toBitwardenPendingItemTypeOrNull()
                        ?: return@forEach
                    val queued = bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = source.id,
                        itemType = pendingItemType
                    )?.isSuccess == true
                    if (!queued) return@forEach
                    queuedBitwardenVaultIds += vaultId
                }

                is SecureItemOwnership.KeePass,
                is SecureItemOwnership.Conflict -> return@forEach
            }

            val storedItemData = prepareSecureItemDataForMdbx(source, isCopy = false)
                ?: return@forEach
            prepared += source.asMdbxBatchMove(
                databaseId = databaseId,
                folderId = folderId,
                storedItemData = storedItemData
            )
        }
        if (prepared.isEmpty()) {
            return MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }

        return runCatching {
            secureRepository.updateItems(prepared)
            queuedBitwardenVaultIds.forEach { vaultId ->
                bitwardenRepository?.requestLocalMutationSync(vaultId)
            }
            MdbxSecureItemBatchResult(
                successCount = prepared.size,
                failedCount = items.size - prepared.size
            )
        }.getOrElse { error ->
            Log.e("PasswordViewModel", "MDBX secure item batch move failed", error)
            MdbxSecureItemBatchResult(successCount = 0, failedCount = items.size)
        }
    }

    private fun prepareSecureItemDataForMdbx(item: SecureItem, isCopy: Boolean): String? {
        return when (item.itemType) {
            ItemType.PASSWORD -> null
            ItemType.TOTP -> {
                val totpData = parseStoredTotpData(item) ?: return null
                val detachedData = TotpDataResolver.normalizeTotpData(totpData).copy(
                    boundPasswordId = null,
                    categoryId = null,
                    keepassDatabaseId = null
                )
                val plainValue = Json.encodeToString(detachedData)
                if (isCopy) {
                    encodeStoredSensitiveValueForNewWrite(plainValue)
                } else {
                    encodeStoredSensitiveValueForCopy(item.itemData, plainValue)
                }
            }

            ItemType.BANK_CARD,
            ItemType.DOCUMENT,
            ItemType.BILLING_ADDRESS,
            ItemType.PAYMENT_ACCOUNT -> if (isCopy) {
                encodeStoredSensitiveValueForNewWrite(decryptStoredSensitiveValue(item.itemData))
            } else {
                item.itemData
            }

            ItemType.NOTE -> item.itemData
        }
    }

    private fun ItemType.toBitwardenPendingItemTypeOrNull(): String? = when (this) {
        ItemType.PASSWORD -> null
        ItemType.TOTP -> BitwardenPendingOperation.ITEM_TYPE_TOTP
        ItemType.BANK_CARD -> BitwardenPendingOperation.ITEM_TYPE_CARD
        ItemType.DOCUMENT -> BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
        ItemType.BILLING_ADDRESS -> BitwardenPendingOperation.ITEM_TYPE_BILLING_ADDRESS
        ItemType.PAYMENT_ACCOUNT -> BitwardenPendingOperation.ITEM_TYPE_PAYMENT_ACCOUNT
        ItemType.NOTE -> BitwardenPendingOperation.ITEM_TYPE_NOTE
    }

    private fun ItemType.toOperationLogItemTypeOrNull(): takagi.ru.monica.data.OperationLogItemType? = when (this) {
        ItemType.PASSWORD -> null
        ItemType.TOTP -> takagi.ru.monica.data.OperationLogItemType.TOTP
        ItemType.BANK_CARD -> takagi.ru.monica.data.OperationLogItemType.BANK_CARD
        ItemType.DOCUMENT -> takagi.ru.monica.data.OperationLogItemType.DOCUMENT
        ItemType.BILLING_ADDRESS -> takagi.ru.monica.data.OperationLogItemType.BILLING_ADDRESS
        ItemType.PAYMENT_ACCOUNT -> takagi.ru.monica.data.OperationLogItemType.PAYMENT_ACCOUNT
        ItemType.NOTE -> takagi.ru.monica.data.OperationLogItemType.NOTE
    }

    suspend fun copyBoundTotpsForPasswordCopies(idPairs: List<Pair<Long, Long>>): Int {
        val secureRepository = secureItemRepository ?: return 0
        if (idPairs.isEmpty()) return 0

        val sourceIds = idPairs.map { it.first }.distinct()
        val newIds = idPairs.map { it.second }.distinct()
        val sourcePasswords = repository.getPasswordsByIds(sourceIds).associateBy { it.id }
        val newPasswords = repository.getPasswordsByIds(newIds).associateBy { it.id }
        val storedTotps = secureRepository.getItemsByType(ItemType.TOTP)
            .first()
            .mapNotNull { item ->
                val data = parseStoredTotpData(item)
                    ?: return@mapNotNull null
                item to data
            }

        val copiedNewPasswordIds = mutableSetOf<Long>()
        val pendingCopies = mutableListOf<BoundTotpPendingCopy>()
        idPairs.forEach { (sourceId, newId) ->
            val sourcePassword = sourcePasswords[sourceId] ?: return@forEach
            val newPassword = newPasswords[newId] ?: return@forEach
            if (newPassword.mdbxDatabaseId == null || !copiedNewPasswordIds.add(newId)) {
                return@forEach
            }

            val sourceTotp = resolveBoundTotpCopySource(
                sourcePassword = sourcePassword,
                storedTotps = storedTotps
            ) ?: return@forEach

            runCatching {
                val now = Date()
                val normalizedData = TotpDataResolver.normalizeTotpData(sourceTotp.data).copy(
                    boundPasswordId = newPassword.id,
                    categoryId = null,
                    keepassDatabaseId = null
                )
                if (normalizedData.secret.isBlank()) return@runCatching

                val copiedItem = sourceTotp.item?.copy(
                    id = 0,
                    title = sourceTotp.title,
                    notes = sourceTotp.notes,
                    itemData = encodeStoredSensitiveValueForCopy(
                        sourceTotp.item.itemData,
                        Json.encodeToString(normalizedData)
                    ),
                    categoryId = null,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    mdbxDatabaseId = newPassword.mdbxDatabaseId,
                    mdbxFolderId = newPassword.mdbxFolderId,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    syncStatus = "NONE",
                    replicaGroupId = null,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now
                ) ?: SecureItem(
                    itemType = ItemType.TOTP,
                    title = sourceTotp.title,
                    notes = sourceTotp.notes,
                    itemData = Json.encodeToString(normalizedData),
                    isFavorite = false,
                    categoryId = null,
                    mdbxDatabaseId = newPassword.mdbxDatabaseId,
                    mdbxFolderId = newPassword.mdbxFolderId,
                    createdAt = now,
                    updatedAt = now
                )

                val authenticatorPayload = TotpDataResolver.toBitwardenPayload(sourceTotp.title, normalizedData)
                pendingCopies += BoundTotpPendingCopy(
                    targetPassword = newPassword,
                    item = copiedItem,
                    authenticatorPayload = authenticatorPayload
                )
            }.onFailure { error ->
                Log.w(
                    "PasswordViewModel",
                    "Failed to copy bound TOTP for password copy $sourceId -> $newId: ${error.message}"
                )
            }
        }
        if (pendingCopies.isEmpty()) return 0

        val createdIds = runCatching {
            secureRepository.insertItems(pendingCopies.map(BoundTotpPendingCopy::item))
        }.getOrElse { error ->
            Log.w(
                "PasswordViewModel",
                "Failed to batch-copy ${pendingCopies.size} bound TOTP items: ${error.message}"
            )
            return 0
        }
        check(createdIds.size == pendingCopies.size) {
            "Bound TOTP batch copy returned an incomplete ID list"
        }
        val passwordAuthenticatorUpdates = pendingCopies.mapNotNull { pending ->
            pending.authenticatorPayload
                .takeIf(String::isNotBlank)
                ?.takeIf {
                    decryptStoredSensitiveValue(pending.targetPassword.authenticatorKey) != it
                }
                ?.let { authenticatorPayload ->
                    pending.targetPassword.copy(
                        authenticatorKey = encodeAuthenticatorKeyForStorage(authenticatorPayload),
                        updatedAt = Date()
                    )
                }
        }
        runCatching {
            repository.updatePasswordEntries(passwordAuthenticatorUpdates)
        }.onFailure { error ->
            Log.w(
                "PasswordViewModel",
                "Failed to batch-update ${passwordAuthenticatorUpdates.size} copied password authenticator payloads: ${error.message}"
            )
        }
        return createdIds.size
    }

    private data class BoundTotpCopySource(
        val item: SecureItem?,
        val data: TotpData,
        val title: String,
        val notes: String
    )

    private data class BoundTotpPendingCopy(
        val targetPassword: PasswordEntry,
        val item: SecureItem,
        val authenticatorPayload: String
    )

    private fun resolveBoundTotpCopySource(
        sourcePassword: PasswordEntry,
        storedTotps: List<Pair<SecureItem, TotpData>>
    ): BoundTotpCopySource? {
        val passwordTotpData = parseStoredAuthenticatorKey(sourcePassword)?.copy(
            boundPasswordId = sourcePassword.id,
            categoryId = sourcePassword.categoryId
        )
        val passwordTotpKey = passwordTotpData?.let(::buildTotpCopyIdentityKey)

        val candidates = storedTotps.filter { (_, data) -> data.boundPasswordId == sourcePassword.id }
        val preferredStored = candidates.firstOrNull { (item, data) ->
            item.mdbxDatabaseId == sourcePassword.mdbxDatabaseId &&
                item.bitwardenVaultId == sourcePassword.bitwardenVaultId &&
                (passwordTotpKey == null || buildTotpCopyIdentityKey(data) == passwordTotpKey)
        } ?: candidates.firstOrNull { (_, data) ->
            passwordTotpKey != null && buildTotpCopyIdentityKey(data) == passwordTotpKey
        } ?: candidates.firstOrNull()

        if (preferredStored != null) {
            val (item, data) = preferredStored
            return BoundTotpCopySource(
                item = item,
                data = data,
                title = item.title,
                notes = item.notes
            )
        }

        return passwordTotpData?.let { data ->
            BoundTotpCopySource(
                item = null,
                data = data,
                title = sourcePassword.title,
                notes = strings.get(R.string.entry_message_from_password, sourcePassword.title)
            )
        }
    }

    private fun buildTotpCopyIdentityKey(data: TotpData): String {
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
    
    /**
     * Verify master password
     */
    fun verifyMasterPassword(password: String): Boolean {
        return securityManager.verifyMasterPassword(password)
    }
    
    /**
     * Reset all application data - used for forgot password scenario
     * Supports selective clearing of different data categories
     */
    fun resetAllData(
        clearPasswords: Boolean = true,
        clearTotp: Boolean = true,
        clearDocuments: Boolean = true,
        clearBankCards: Boolean = true,
        clearGeneratorHistory: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                // Clear selected data categories
                if (clearPasswords) {
                    repository.deleteAllPasswordEntries()
                }
                
                if (secureItemRepository != null) {
                    if (clearTotp) {
                        secureItemRepository.deleteAllTotpEntries()
                    }
                    
                    if (clearDocuments) {
                        secureItemRepository.deleteAllDocuments()
                    }
                    
                    if (clearBankCards) {
                        secureItemRepository.deleteAllBankCards()
                    }
                }
                
                if (clearGeneratorHistory && passwordHistoryManager != null) {
                    passwordHistoryManager.clearHistory()
                }
                
                // Always clear security data when resetting
                securityManager.clearSecurityData()
                
                // Reset authentication state
                _isAuthenticated.value = false
            } catch (e: Exception) {
                // Handle error - log it
                Log.e("PasswordViewModel", "Error clearing data", e)
            }
        }
    }
    
    /**
     * Change master password
     * 修改主密码并重新加密所有数据
     */
    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            // 1. 验证当前密码
            if (!securityManager.verifyMasterPassword(currentPassword)) {
                // TODO: 通知UI密码错误
                return@launch
            }
            
            // 2. 获取所有加密数据
            val allPasswords = repository.getAllPasswordEntries().first()
            
            // 3. 使用当前密码解密所有数据
            val decryptedPasswords = allPasswords.map { entry ->
                entry.copy(password = decryptForDisplay(entry.password))
            }
            
            // 4. 设置新密码
            securityManager.setMasterPassword(newPassword)
            
            // 5. 使用新密码重新加密所有数据
            decryptedPasswords.forEach { entry ->
                repository.updatePasswordEntry(entry.copy(
                    password = securityManager.encryptData(entry.password),
                    updatedAt = Date()
                ))
            }
            
            // 6. 重新认证
            _isAuthenticated.value = true
        }
    }
    
    /**
     * Save security questions
     * 保存密保问题
     */
    fun saveSecurityQuestions(questions: List<Pair<String, String>>) {
        viewModelScope.launch {
            // TODO: 保存到DataStore或数据库
            // 答案应该加密存储
            questions.forEach { (question, answer) ->
                val encryptedAnswer = securityManager.encryptData(answer.lowercase())
                // 存储 question 和 encryptedAnswer
            }
        }
    }

    fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String) {
        viewModelScope.launch {
            repository.updateAppAssociationByWebsite(website, packageName, appName)
        }
    }

    fun updateAppAssociationByTitle(title: String, packageName: String, appName: String) {
        viewModelScope.launch {
            repository.updateAppAssociationByTitle(title, packageName, appName)
        }
    }

    // ==========================================
    // Grouping Helpers
    // ==========================================

    private fun getPasswordInfoKey(entry: PasswordEntry): String {
        return "${entry.title}|${entry.website}|${entry.username}|${entry.notes}|${entry.appPackageName}|${entry.appName}"
    }

    private fun applyCategoryBinding(entry: PasswordEntry): PasswordEntry {
        val filterBoundEntry = when (val filter = _categoryFilter.value) {
            is CategoryFilter.KeePassDatabase -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassDatabaseStarred -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.MdbxDatabase -> {
                if (entry.mdbxDatabaseId == null) entry.copy(mdbxDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassGroupFilter -> {
                if (entry.keepassDatabaseId == null) {
                    entry.copy(
                        keepassDatabaseId = filter.databaseId,
                        keepassGroupPath = entry.keepassGroupPath ?: filter.groupPath
                    )
                } else if (entry.keepassGroupPath.isNullOrBlank()) {
                    entry.copy(keepassGroupPath = filter.groupPath)
                } else {
                    entry
                }
            }
            else -> entry
        }

        // Password category assignment should not silently change storage
        // ownership for local Monica items. Only entries that already belong to
        // Bitwarden may inherit/update folder linkage from a linked category.

        val categoryId = filterBoundEntry.categoryId ?: return filterBoundEntry
        val category = categories.value.find { it.id == categoryId } ?: return filterBoundEntry

        // KeePass 条目保持独立，不参与 Bitwarden 自动绑定
        if (filterBoundEntry.keepassDatabaseId != null) return filterBoundEntry

        val alreadyBitwardenOwned = filterBoundEntry.bitwardenVaultId != null ||
            !filterBoundEntry.bitwardenCipherId.isNullOrBlank()
        if (!alreadyBitwardenOwned) {
            return filterBoundEntry.copy(
                bitwardenVaultId = null,
                bitwardenFolderId = null,
                bitwardenLocalModified = false
            )
        }

        // 分类未绑定 Bitwarden：清理“待上传”绑定（已同步条目保持映射不动）
        if (category.bitwardenVaultId == null || category.bitwardenFolderId == null) {
            return if (filterBoundEntry.bitwardenCipherId == null) {
                filterBoundEntry.copy(
                    bitwardenVaultId = null,
                    bitwardenFolderId = null,
                    bitwardenLocalModified = false
                )
            } else {
                filterBoundEntry
            }
        }
        
        // 自动绑定到分类关联的 Bitwarden 文件夹
        return filterBoundEntry.copy(
            bitwardenVaultId = category.bitwardenVaultId,
            bitwardenFolderId = category.bitwardenFolderId,
            // 如果是已同步的条目，且文件夹改变了，标记为本地修改
            bitwardenLocalModified = if (filterBoundEntry.bitwardenCipherId != null && filterBoundEntry.bitwardenFolderId != category.bitwardenFolderId) true else filterBoundEntry.bitwardenLocalModified
        )
    }

    /**
     * Save a group of passwords.
     * Updates existing entries to preserve IDs (and TOTP links), creates new ones if needed,
     * and deletes removed ones.
     * The callback receives the ID of the first password (for TOTP binding).
     */
    fun saveGroupedPasswords(
        originalIds: List<Long>,
        commonEntry: PasswordEntry, // Contains common info and ONE password (ignored)
        passwords: List<String>,
        customFields: List<CustomFieldDraft> = emptyList(), // 自定义字段
        onComplete: (firstPasswordId: Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val firstPasswordId = withContext(Dispatchers.IO) {
                saveGroupedPasswordsInternal(
                    originalIds = originalIds,
                    commonEntry = commonEntry,
                    passwords = passwords,
                    customFields = customFields,
                    skipCategoryBinding = false
                )
            }
            onComplete(firstPasswordId)
        }
    }

    fun savePasswordsAcrossTargets(
        originalIds: List<Long>,
        commonEntry: PasswordEntry,
        passwords: List<String>,
        targets: List<StorageTarget>,
        customFields: List<CustomFieldDraft> = emptyList(),
        onCompleteWithIds: (firstPasswordId: Long?, savedPasswordIds: List<Long>) -> Unit = { _, _ -> },
        onComplete: (firstPasswordId: Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val requestedTargetKeys = targets.distinctBy(StorageTarget::stableKey)
                .map(StorageTarget::stableKey)
            val saveResult = try {
                withContext(Dispatchers.IO) {
                    val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
                    if (distinctTargets.isEmpty()) {
                        Log.w("PasswordViewModel", "savePasswordsAcrossTargets blocked because target list is empty")
                        return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                    }
                    if (!canWriteKeePassTargets(distinctTargets)) {
                        Log.w(
                            "PasswordViewModel",
                            "savePasswordsAcrossTargets blocked because a KeePass target is unavailable targets=$requestedTargetKeys"
                        )
                        return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                    }

                val currentEntry = originalIds.firstOrNull()?.let { repository.getPasswordEntryById(it) }
                val replicaGroupId = currentEntry?.replicaGroupId
                    ?.takeIf { it.isNotBlank() }
                    ?: commonEntry.replicaGroupId?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val selectedTargetKeys = distinctTargets
                    .map(StorageTarget::stableKey)
                    .toSet()
                val allEntries = repository.getAllPasswordEntries().first()
                val originalEntries = originalIds.mapNotNull { id ->
                    allEntries.firstOrNull { it.id == id }
                }
                val existingReplicasByKey = allEntries
                    .filter {
                        it.replicaGroupId == replicaGroupId &&
                            !it.isDeleted &&
                            !it.isArchived
                    }
                    .groupBy { it.toStorageTarget().stableKey }
                val currentEntryTarget = currentEntry?.toStorageTarget()
                val currentTarget = currentEntryTarget
                    ?.takeIf { it.stableKey in selectedTargetKeys }
                    ?: distinctTargets.first()
                val currentTargetOriginalIds = originalEntries
                    .filter {
                        it.toStorageTarget().stableKey == currentTarget.stableKey
                    }
                    .map { it.id }
                    .ifEmpty {
                        existingReplicasByKey[currentTarget.stableKey]
                            .orEmpty()
                            .sortedBy { it.id }
                            .map { it.id }
                    }
                    .ifEmpty {
                        if (currentEntryTarget?.stableKey in selectedTargetKeys && currentEntry != null) {
                            listOf(currentEntry.id)
                        } else {
                            emptyList()
                        }
                    }

                val updatedCurrentEntry = currentTarget.applyToPasswordEntry(
                    commonEntry,
                    replicaGroupId = replicaGroupId
                )
                val initialId = saveGroupedPasswordsInternal(
                    originalIds = currentTargetOriginalIds.ifEmpty { originalIds },
                    commonEntry = updatedCurrentEntry,
                    passwords = passwords,
                    customFields = customFields,
                    skipCategoryBinding = true
                )

                    if (initialId == null) {
                        Log.e(
                            "PasswordViewModel",
                            "savePasswordsAcrossTargets failed current target=${currentTarget.stableKey} originalIds=$originalIds targets=$requestedTargetKeys"
                        )
                        return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                    }
                    val savedTargetFirstIds = mutableListOf(initialId)

                distinctTargets
                    .filter { target ->
                        target.stableKey != currentTarget.stableKey &&
                            target.stableKey in selectedTargetKeys
                    }
                    .forEach { target ->
                        val existingTargetIds = existingReplicasByKey[target.stableKey]
                            .orEmpty()
                            .sortedBy { it.id }
                            .map { it.id }
                        val replicaEntry = target.applyToPasswordEntry(
                            commonEntry,
                            replicaGroupId = replicaGroupId
                        )
                        val createdId = saveGroupedPasswordsInternal(
                            originalIds = existingTargetIds,
                            commonEntry = replicaEntry,
                            passwords = passwords,
                            customFields = customFields,
                            skipCategoryBinding = true
                        )
                        if (createdId == null) {
                            Log.e(
                                "PasswordViewModel",
                                "savePasswordsAcrossTargets skipped failed target=${target.stableKey}"
                            )
                        } else {
                            savedTargetFirstIds += createdId
                        }
                    }

                val activeReplicas = repository.getAllPasswordEntries()
                    .first()
                    .filter {
                        it.replicaGroupId == replicaGroupId &&
                            !it.isDeleted &&
                            !it.isArchived
                    }
                val staleReplicas = activeReplicas.filter {
                    it.toStorageTarget().stableKey !in selectedTargetKeys
                }
                if (staleReplicas.isNotEmpty()) {
                    if (distinctTargets.size == 1 && originalIds.isNotEmpty()) {
                        repository.deletePasswordEntries(staleReplicas)
                        Log.i(
                            "PasswordViewModel",
                            "Moved edited password entry by removing ${staleReplicas.size} old storage replicas: ids=${staleReplicas.map { it.id }}"
                        )
                    } else {
                        Log.w(
                            "PasswordViewModel",
                            "Preserving ${staleReplicas.size} existing password replicas not present in the edited target selection: ids=${staleReplicas.map { it.id }}"
                        )
                    }
                }

                    PasswordSaveAcrossTargetsResult(
                        firstPasswordId = initialId,
                        savedPasswordIds = savedTargetFirstIds.distinct()
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "PasswordViewModel",
                    "savePasswordsAcrossTargets crashed originalIds=$originalIds targets=$requestedTargetKeys error=${e::class.java.simpleName}: ${e.message}",
                    e
                )
                PasswordSaveAcrossTargetsResult(null, emptyList())
            }

            onComplete(saveResult.firstPasswordId)
            onCompleteWithIds(saveResult.firstPasswordId, saveResult.savedPasswordIds)
        }
    }

    fun saveCredentialsAcrossTargets(
        commonEntry: PasswordEntry,
        credentials: List<PasswordCredentialDraft>,
        targets: List<StorageTarget>,
        customFields: List<CustomFieldDraft> = emptyList(),
        onComplete: (List<SavedPasswordCredential>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
            val normalizedCredentials = credentials.map {
                it.copy(
                    username = it.username.trim(),
                    password = it.password.trim()
                )
            }
            val result = try {
                withContext(Dispatchers.IO) {
                    if (distinctTargets.isEmpty() || normalizedCredentials.isEmpty()) {
                        emptyList()
                    } else if (!canWriteKeePassTargets(distinctTargets)) {
                        Log.w(
                            "PasswordViewModel",
                            "saveCredentialsAcrossTargets blocked because a KeePass target is unavailable"
                        )
                        emptyList()
                    } else {
                        saveIndependentCredentialsInternal(
                            commonEntry = commonEntry,
                            credentials = normalizedCredentials,
                            targets = distinctTargets,
                            customFields = customFields
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "PasswordViewModel",
                    "saveCredentialsAcrossTargets crashed credentials=${normalizedCredentials.size} targets=${distinctTargets.map(StorageTarget::stableKey)}",
                    e
                )
                emptyList()
            }
            onComplete(result)
        }
    }

    private suspend fun saveIndependentCredentialsInternal(
        commonEntry: PasswordEntry,
        credentials: List<PasswordCredentialDraft>,
        targets: List<StorageTarget>,
        customFields: List<CustomFieldDraft>
    ): List<SavedPasswordCredential> {
        val templates = buildIndependentPasswordCredentialTemplates(commonEntry, credentials)
        val savedCredentials = mutableListOf<SavedPasswordCredential>()
        val allCreatedIds = mutableListOf<Long>()

        try {
        templates.forEachIndexed { credentialIndex, credentialTemplate ->
            val replicaGroupId = requireNotNull(credentialTemplate.replicaGroupId)
            val savedIds = mutableListOf<Long>()
            val credentialCustomFields = mergePasswordCredentialCustomFields(
                commonFields = customFields,
                credentialFields = credentials[credentialIndex].customFields
            )
            targets.forEach { target ->
                val targetEntry = target.applyToPasswordEntry(
                    credentialTemplate,
                    replicaGroupId = replicaGroupId
                )
                val savedId = saveGroupedPasswordsInternal(
                    originalIds = emptyList(),
                    commonEntry = targetEntry,
                    passwords = listOf(credentialTemplate.password),
                    customFields = credentialCustomFields,
                    skipCategoryBinding = true
                )
                if (savedId == null) {
                    Log.e(
                        "PasswordViewModel",
                        "Independent credential save failed credential=$credentialIndex target=${target.stableKey}"
                    )
                    rollbackIndependentCredentialCreates(allCreatedIds)
                    return emptyList()
                }
                savedIds += savedId
                allCreatedIds += savedId
            }
            savedCredentials += SavedPasswordCredential(
                credentialIndex = credentialIndex,
                firstPasswordId = savedIds.first(),
                savedPasswordIds = savedIds.distinct(),
                replicaGroupId = replicaGroupId
            )
        }

        return savedCredentials
        } catch (e: Exception) {
            rollbackIndependentCredentialCreates(allCreatedIds)
            throw e
        }
    }

    private suspend fun rollbackIndependentCredentialCreates(createdIds: List<Long>) {
        withContext(NonCancellable) {
            createdIds.asReversed().forEach { createdId ->
                runCatching { rollbackPasswordTransferTargetAwait(createdId) }
                    .onFailure { rollbackError ->
                        Log.e(
                            "PasswordViewModel",
                            "Independent credential rollback failed entryId=$createdId",
                            rollbackError
                        )
                    }
            }
        }
    }

    private data class PasswordSaveAcrossTargetsResult(
        val firstPasswordId: Long?,
        val savedPasswordIds: List<Long>
    )

    private suspend fun canWriteKeePassTargets(targets: List<StorageTarget>): Boolean {
        val dao = localKeePassDatabaseDao ?: return true
        return targets.all { target ->
            val keepassTarget = target as? StorageTarget.KeePass ?: return@all true
            val database = dao.getDatabaseById(keepassTarget.databaseId) ?: return@all false
            database.canWriteExternally() && database.writeOperationAvailability().canOperate
        }
    }

    private suspend fun canWriteKeePassDatabase(databaseId: Long): Boolean {
        val dao = localKeePassDatabaseDao ?: return true
        val database = dao.getDatabaseById(databaseId) ?: return false
        return database.canWriteExternally() && database.writeOperationAvailability().canOperate
    }

    private fun takagi.ru.monica.data.LocalKeePassDatabase.canWriteExternally(): Boolean {
        if (resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) return true
        val resolver = appContext?.contentResolver ?: return false
        return resolver.keePassUriPermissionState(Uri.parse(resolvedActiveFilePath())) == KeePassUriPermissionState.READ_WRITE
    }

    private suspend fun saveGroupedPasswordsInternal(
        originalIds: List<Long>,
        commonEntry: PasswordEntry,
        passwords: List<String>,
        customFields: List<CustomFieldDraft> = emptyList(),
        skipCategoryBinding: Boolean
    ): Long? {
        var firstId: Long? = null
        val normalizedPasswords = passwords.map { it.trim() }
        val normalizedInput = normalizedPasswords.filter { it.isNotEmpty() }
        val preservedUnreadablePasswords = if (normalizedInput.isEmpty() && originalIds.isNotEmpty()) {
            originalIds.mapNotNull { id ->
                val existing = repository.getPasswordEntryById(id) ?: return@mapNotNull null
                if (shouldPreserveUnreadableBitwardenPassword(existing, "")) "" else null
            }
        } else {
            emptyList()
        }
        val hasPreservedUnreadablePassword = preservedUnreadablePasswords.isNotEmpty()

        val effectivePasswords = when {
            normalizedInput.isNotEmpty() -> normalizedInput
            commonEntry.loginType.equals("SSO", ignoreCase = true) -> listOf("")
            hasPreservedUnreadablePassword -> preservedUnreadablePasswords
            else -> listOf("")
        }

        val boundCommonEntry = if (skipCategoryBinding) {
            commonEntry
        } else {
            applyCategoryBinding(commonEntry)
        }

        val pendingMdbxCreates = mutableListOf<Pair<Int, PasswordEntry>>()
        effectivePasswords.forEachIndexed { index, password ->
            if (index < originalIds.size) {
                val id = originalIds[index]
                if (index == 0) firstId = id
                val draftEntry = boundCommonEntry.copy(
                    id = id,
                    password = password
                )
                val existingEntry = repository.getPasswordEntryById(id)
                val updatedEntry = existingEntry?.copy(
                    title = draftEntry.title,
                    website = draftEntry.website,
                    username = draftEntry.username,
                    password = draftEntry.password,
                    notes = draftEntry.notes,
                    isFavorite = draftEntry.isFavorite,
                    appPackageName = draftEntry.appPackageName,
                    appName = draftEntry.appName,
                    email = draftEntry.email,
                    phone = draftEntry.phone,
                    addressLine = draftEntry.addressLine,
                    city = draftEntry.city,
                    state = draftEntry.state,
                    zipCode = draftEntry.zipCode,
                    country = draftEntry.country,
                    creditCardNumber = draftEntry.creditCardNumber,
                    creditCardHolder = draftEntry.creditCardHolder,
                    creditCardExpiry = draftEntry.creditCardExpiry,
                    creditCardCVV = draftEntry.creditCardCVV,
                    categoryId = draftEntry.categoryId,
                    boundNoteId = draftEntry.boundNoteId,
                    keepassDatabaseId = draftEntry.keepassDatabaseId,
                    keepassGroupPath = draftEntry.keepassGroupPath,
                    mdbxDatabaseId = draftEntry.mdbxDatabaseId,
                    authenticatorKey = draftEntry.authenticatorKey,
                    passkeyBindings = draftEntry.passkeyBindings,
                    sshKeyData = draftEntry.sshKeyData,
                    loginType = draftEntry.loginType,
                    ssoProvider = draftEntry.ssoProvider,
                    ssoRefEntryId = draftEntry.ssoRefEntryId,
                    replicaGroupId = draftEntry.replicaGroupId,
                    bitwardenVaultId = draftEntry.bitwardenVaultId,
                    bitwardenFolderId = draftEntry.bitwardenFolderId,
                    customIconType = draftEntry.customIconType,
                    customIconValue = draftEntry.customIconValue,
                    customIconUpdatedAt = draftEntry.customIconUpdatedAt
                ) ?: draftEntry
                val entryCustomFields = if (index == 0) customFields else emptyList()
                val updated = updatePasswordEntryInternal(
                    entry = updatedEntry,
                    customFieldsOverride = entryCustomFields
                )
                if (!updated) {
                    Log.e(
                        "PasswordViewModel",
                        "saveGroupedPasswords aborted due to password update failure entryId=$id target=${draftEntry.toStorageTarget().stableKey}"
                    )
                    return null
                }
            } else {
                val newEntry = boundCommonEntry.copy(
                    id = 0,
                    password = password
                )
                if (newEntry.isPureMdbxCreateTarget()) {
                    pendingMdbxCreates += index to newEntry
                } else {
                    val entryCustomFields = if (index == 0) customFields else emptyList()
                    val newId = createPasswordEntryInternal(
                        entry = newEntry,
                        includeDetailedLog = false,
                        skipCategoryBinding = skipCategoryBinding,
                        customFieldsOverride = entryCustomFields
                    )
                    if (newId == null) {
                        Log.e("PasswordViewModel", "saveGroupedPasswords aborted due to KeePass write failure")
                        return firstId ?: originalIds.firstOrNull()
                    }
                    if (index == 0) firstId = newId
                }
            }
        }

        if (pendingMdbxCreates.isNotEmpty()) {
            val createdIds = createMdbxPasswordEntriesBatch(pendingMdbxCreates.map { it.second })
            if (createdIds.size != pendingMdbxCreates.size) {
                Log.e("PasswordViewModel", "saveGroupedPasswords aborted due to MDBX batch insert mismatch")
                return firstId ?: originalIds.firstOrNull()
            }
            pendingMdbxCreates.forEachIndexed { createdIndex, (passwordIndex, _) ->
                if (passwordIndex == 0) firstId = createdIds[createdIndex]
            }
        }

        if (originalIds.size > effectivePasswords.size) {
            val toDelete = originalIds.subList(effectivePasswords.size, originalIds.size)
            val entriesToDelete = toDelete.mapNotNull { id -> repository.getPasswordEntryById(id) }
            if (entriesToDelete.isNotEmpty()) {
                deletePasswordEntriesBatch(entriesToDelete)
            }
        }

        firstId?.let { entryId ->
            saveCustomFieldsForEntry(entryId, customFields)
        }

        return firstId
    }

    private fun PasswordEntry.isPureMdbxCreateTarget(): Boolean =
        mdbxDatabaseId != null &&
            keepassDatabaseId == null &&
            bitwardenVaultId == null

    private suspend fun createMdbxPasswordEntriesBatch(entries: List<PasswordEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        val encryptedEntries = entries.map { entry ->
            val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordInsert(entry)
            if (normalizedEntry.hasOwnershipConflict()) {
                Log.w("PasswordViewModel", "Blocked MDBX batch create because of ownership conflict")
                return emptyList()
            }
            normalizedEntry.copy(
                password = securityManager.encryptData(normalizedEntry.password),
                authenticatorKey = encodeAuthenticatorKeyForStorage(normalizedEntry.authenticatorKey),
                createdAt = Date(),
                updatedAt = Date()
            )
        }
        return repository.insertPasswordEntries(encryptedEntries)
    }

    suspend fun createMdbxPasswordEntriesBatchAlreadyEncrypted(entries: List<PasswordEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        val encryptedEntries = entries.map { entry ->
            val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordInsert(entry)
            if (normalizedEntry.hasOwnershipConflict()) {
                Log.w("PasswordViewModel", "Blocked MDBX batch copy because of ownership conflict")
                return emptyList()
            }
            normalizedEntry.copy(
                password = normalizedEntry.password,
                authenticatorKey = encodeAuthenticatorKeyForStorage(normalizedEntry.authenticatorKey),
                createdAt = Date(),
                updatedAt = Date()
            )
        }
        return repository.insertPasswordEntries(encryptedEntries)
    }
    
    // =============== 自定义字段相关方法 ===============
    
    /**
     * 获取指定密码条目的自定义字段（Flow）
     */
    fun getCustomFieldsByEntryId(entryId: Long): Flow<List<CustomField>> {
        return customFieldRepository?.getFieldsByEntryId(entryId) ?: flowOf(emptyList())
    }
    
    /**
     * 获取指定密码条目的自定义字段（同步版本）
     */
    suspend fun getCustomFieldsByEntryIdSync(entryId: Long): List<CustomField> {
        return customFieldRepository?.getFieldsByEntryIdSync(entryId) ?: emptyList()
    }
    
    /**
     * 保存密码条目的自定义字段
     * 同时更新密码条目的 updatedAt 以触发同步
     */
    suspend fun saveCustomFieldsForEntry(entryId: Long, fields: List<CustomFieldDraft>) {
        customFieldRepository?.saveFieldsForEntry(entryId, fields)

        val updatedAt = java.util.Date()
        val entry = repository.getPasswordEntryById(entryId)
        val bitwardenVaultId = entry?.bitwardenVaultId
        if (entry != null && bitwardenVaultId != null) {
            repository.updatePasswordEntry(
                entry.copy(
                    bitwardenLocalModified = true,
                    updatedAt = updatedAt
                )
            )
            // 字段已经写入 Room 后再请求同步，避免后台任务读取到旧字段列表。
            bitwardenRepository?.requestLocalMutationSync(bitwardenVaultId)
        } else if (entry?.mdbxDatabaseId != null) {
            // The first native write precedes the Room custom-field replacement.
            // Commit the final field set before reporting save success, including removals.
            repository.updatePasswordEntry(entry.copy(updatedAt = updatedAt))
        } else {
            // 更新密码条目的 updatedAt 以确保 WebDAV 同步能检测到自定义字段的变化
            repository.updatePasswordUpdatedAt(entryId, updatedAt)
        }
    }

    private suspend fun copyCustomFieldsForEntryCopy(
        sourceEntryId: Long,
        targetEntryId: Long
    ) {
        if (sourceEntryId <= 0 || targetEntryId <= 0 || sourceEntryId == targetEntryId) return
        val fieldRepository = customFieldRepository ?: return
        val fields = fieldRepository.getFieldsByEntryIdSync(sourceEntryId)
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, field ->
                field.copy(
                    id = 0,
                    entryId = targetEntryId,
                    sortOrder = index
                )
            }
        if (fields.isNotEmpty()) {
            fieldRepository.saveFieldsForEntries(mapOf(targetEntryId to fields))
        }
    }
    
    /**
     * 批量获取多个条目的自定义字段（用于列表显示优化）
     */
    suspend fun getCustomFieldsByEntryIds(entryIds: List<Long>): Map<Long, List<CustomField>> {
        return customFieldRepository?.getFieldsByEntryIds(entryIds) ?: emptyMap()
    }

    /**
     * 为选中的密码条目应用同一个手动堆叠分组。
     * 使用内部自定义字段持久化，优先级高于自动堆叠规则。
     *
     * @return 实际写入的条目数量
     */
    suspend fun applyManualStack(entryIds: List<Long>): Int {
        return applyManualStackMode(entryIds, ManualStackMode.STACK)
    }

    /**
     * 设置选中条目的堆叠模式：
     * STACK: 写入同一手动堆叠组
     * AUTO_STACK: 清除手动堆叠/不堆叠标记，回归自动堆叠
     * NEVER_STACK: 标记为永不参与堆叠
     */
    suspend fun applyManualStackMode(entryIds: List<Long>, mode: ManualStackMode): Int {
        val validIds = entryIds.distinct().filter { it > 0L }
        if (validIds.isEmpty()) return 0

        val stackGroupId = if (mode == ManualStackMode.STACK) UUID.randomUUID().toString() else null
        val existingFieldsByEntry = getCustomFieldsByEntryIds(validIds)

        validIds.forEach { entryId ->
            val keptFields = existingFieldsByEntry[entryId]
                .orEmpty()
                .asSequence()
                .filterNot {
                    it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE ||
                        it.title == MONICA_NO_STACK_FIELD_TITLE
                }
                .map { field ->
                    CustomFieldDraft(
                        title = field.title,
                        value = field.value,
                        isProtected = field.isProtected
                    )
                }
                .toMutableList()

            when (mode) {
                ManualStackMode.STACK -> {
                    keptFields += CustomFieldDraft(
                        title = MONICA_MANUAL_STACK_GROUP_FIELD_TITLE,
                        value = stackGroupId.orEmpty(),
                        isProtected = false
                    )
                }
                ManualStackMode.NEVER_STACK -> {
                    keptFields += CustomFieldDraft(
                        title = MONICA_NO_STACK_FIELD_TITLE,
                        value = "1",
                        isProtected = false
                    )
                }
                ManualStackMode.AUTO_STACK -> Unit
            }

            saveCustomFieldsForEntry(entryId, keptFields)
        }

        return validIds.size
    }
    
    /**
     * 搜索包含指定关键词的条目ID（通过自定义字段搜索）
     */
    suspend fun searchEntryIdsByCustomFieldContent(query: String): List<Long> {
        return customFieldRepository?.searchEntryIdsByFieldContent(query) ?: emptyList()
    }
}

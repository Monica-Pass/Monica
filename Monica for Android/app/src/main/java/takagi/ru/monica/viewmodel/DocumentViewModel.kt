package takagi.ru.monica.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.keepass.KeePassCrossDatabaseTransfer
import takagi.ru.monica.keepass.KeePassSecureItemCreateExecutor
import takagi.ru.monica.keepass.KeePassSecureItemDeleteExecutor
import takagi.ru.monica.keepass.KeePassSecureItemUpdateExecutor
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.SecureItemBitwardenTransitionResolver
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.asMonicaLocalCopy
import takagi.ru.monica.data.hasOwnershipConflict
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
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
import java.util.Date
import java.util.UUID

data class ParsedDocumentItem(
    val item: SecureItem,
    val documentData: DocumentData
)

class DocumentViewModel(
    private val repository: SecureItemRepository,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    private val securityManager: SecurityManager? = null
) : ViewModel() {
    private val attachmentFacade = context?.let { AttachmentContainer.facade(it) }
    private data class KeePassMutationIdentity(
        val groupPath: String?,
        val entryUuid: String?,
        val groupUuid: String?
    )

    private val bitwardenRepository = context?.let { BitwardenRepository.getInstance(it.applicationContext) }

    private fun requestBitwardenMutationSync(vaultId: Long?) {
        vaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
    }

    fun getAttachmentBitwardenContext(
        vault: BitwardenVault,
        cipherId: String?
    ): takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext? {
        return bitwardenRepository?.getAttachmentBitwardenContext(vault, cipherId)
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

    private fun decryptStoredSensitiveValue(value: String): String {
        return securityManager
            ?.let { manager -> runCatching { manager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value) }
            ?: value
    }

    private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String {
        if (plainValue.isBlank()) return plainValue
        return securityManager?.encryptDataLegacyCompat(plainValue) ?: plainValue
    }

    private fun encodeDocumentDataForLocalStorage(documentData: DocumentData): String {
        return encodeStoredSensitiveValueForNewWrite(
            CardWalletDataCodec.encodeDocumentData(documentData)
        )
    }

    private fun encodeIncomingItemDataForLocalStorage(itemData: String): String {
        return encodeStoredSensitiveValueForNewWrite(decryptStoredSensitiveValue(itemData))
    }

    init {
        // Legacy binding repair scans the shared secure-item table. Keep it
        // off the main dispatcher so it cannot delay the first document frame.
        viewModelScope.launch(Dispatchers.Default) {
            repairLegacyDetachedKeePassItems()
        }
    }
    
    private val documentListSharingStarted = SharingStarted.WhileSubscribed(5000)
    private val allDocumentsSource: SharedFlow<List<SecureItem>> =
        repository.getItemsByType(ItemType.DOCUMENT)
            .shareIn(
                scope = viewModelScope,
                started = documentListSharingStarted,
                replay = 1,
            )

    // 获取所有证件
    val allDocuments: StateFlow<List<SecureItem>> = allDocumentsSource
        .stateIn(
            scope = viewModelScope,
            started = documentListSharingStarted,
            initialValue = emptyList()
        )

    private val parsedDocumentsStateSource: Flow<LoadedListState<ParsedDocumentItem>> = allDocumentsSource
        .map { items ->
            LoadedListState(
                items = items.map { item ->
                    ParsedDocumentItem(
                        item = item,
                        documentData = parseDocumentData(item.itemData) ?: emptyDocumentData()
                    )
                },
                isReady = true,
            )
        }
        .flowOn(Dispatchers.Default)

    val parsedDocumentsState: StateFlow<LoadedListState<ParsedDocumentItem>> =
        parsedDocumentsStateSource.stateIn(
            scope = viewModelScope,
            started = documentListSharingStarted,
            initialValue = LoadedListState(),
        )

    val parsedDocumentsReady: StateFlow<Boolean> = parsedDocumentsState
        .map { state -> state.isReady }
        .stateIn(
            scope = viewModelScope,
            started = documentListSharingStarted,
            initialValue = false,
        )

    val isLoading: StateFlow<Boolean> = parsedDocumentsReady
        .map { ready -> !ready }
        .stateIn(
            scope = viewModelScope,
            started = documentListSharingStarted,
            initialValue = true,
        )

    val parsedDocuments: StateFlow<List<ParsedDocumentItem>> = parsedDocumentsState
        .map { state -> state.items }
        .stateIn(
            scope = viewModelScope,
            started = documentListSharingStarted,
            initialValue = emptyList()
        )
    
    // 根据ID获取证件
    suspend fun getDocumentById(id: Long): SecureItem? {
        val item = repository.getItemById(id) ?: return null
        return repository.normalizeLegacyDetachedKeePassItem(item, ::hasKeePassDatabase)
    }

    fun syncAllKeePassDocuments() {
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = SyncDiagnostics.nextTaskId("kp-doc-all"),
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = null,
                        itemTypes = setOf(SyncItemKind.DOCUMENT)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncAllKeePassDocumentsNow()
            }
        }
    }

    suspend fun syncAllKeePassDocumentsNow() {
        val taskId = SyncDiagnostics.nextTaskId("kp-doc-all")
        val target = "keepass_compat:document:all"
        val trigger = "CARD_WALLET_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            val dao = localKeePassDatabaseDao ?: run {
                SyncDiagnostics.skipped(taskId, target, trigger, "dao_unavailable", startedAt)
                return
            }
            val dbs = withContext(Dispatchers.IO) { dao.getAllDatabasesSync() }
            dbs.forEach { syncKeePassDocumentsNow(it.id) }
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "scheduledDatabases=${dbs.size}"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            throw error
        }
    }

    fun syncKeePassDocuments(databaseId: Long) {
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = SyncDiagnostics.nextTaskId("kp-doc"),
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.DOCUMENT)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncKeePassDocumentsNow(databaseId)
            }
        }
    }

    suspend fun syncKeePassDocumentsNow(databaseId: Long) {
        val taskId = SyncDiagnostics.nextTaskId("kp-doc")
        val target = "keepass_compat:document:$databaseId"
        val trigger = "READ_LEGACY_SECURE_ITEMS"
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            val bridge = keepassBridge ?: run {
                SyncDiagnostics.skipped(taskId, target, trigger, "bridge_unavailable", startedAt)
                return
            }
            val decision = bridge.legacyProjectionRefreshDecision(
                databaseId,
                takagi.ru.monica.keepass.KeePassProjectionKind.DOCUMENT
            ).getOrThrow()
            if (!decision.needsRefresh) {
                SyncDiagnostics.skipped(taskId, target, trigger, "native_revision_unchanged", startedAt)
                return
            }
            val snapshots = bridge
                .readLegacySecureItems(databaseId, setOf(ItemType.DOCUMENT))
                ?.getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "bridge_or_read_unavailable", startedAt)
                    return
                }

            val existingDocs = repository.getItemsByType(ItemType.DOCUMENT).first()
            snapshots.forEach { snapshot ->
                val incoming = snapshot.item
                val existingByUuid = incoming.keepassEntryUuid
                    ?.takeIf { it.isNotBlank() }
                    ?.let { repository.getItemByKeePassUuid(databaseId, it) }
                val existingBySource = snapshot.sourceMonicaId
                    ?.takeIf { it > 0 }
                    ?.let { sourceId -> repository.getItemById(sourceId) }
                    ?.takeIf { it.itemType == ItemType.DOCUMENT }

                val existing = existingByUuid ?: existingBySource ?: existingDocs.firstOrNull {
                    it.itemType == ItemType.DOCUMENT &&
                        it.keepassDatabaseId == databaseId &&
                        it.keepassGroupPath == incoming.keepassGroupPath &&
                        it.title == incoming.title
                }

                val incomingForLocalStorage = incoming.copy(
                    itemData = encodeIncomingItemDataForLocalStorage(incoming.itemData)
                )

                if (existing == null) {
                    repository.insertItem(incomingForLocalStorage)
                } else {
                    val isInRecycleBin = snapshot.isInRecycleBin
                    val updated = existing.copy(
                        title = incoming.title,
                        notes = incoming.notes,
                        itemData = incomingForLocalStorage.itemData,
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
                setOf(takagi.ru.monica.keepass.KeePassProjectionKind.DOCUMENT)
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
    
    // 添加证件
    fun addDocument(
        title: String,
        documentData: DocumentData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null,
        replicaGroupId: String? = null,
        onCreated: suspend (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val keepassIdentity = resolveKeePassMutationIdentity(
                existingItem = null,
                targetDatabaseId = keepassDatabaseId,
                requestedGroupPath = keepassGroupPath
            )
            val item = SecureItem(
                id = 0,
                itemType = ItemType.DOCUMENT,
                title = title,
                itemData = encodeDocumentDataForLocalStorage(documentData),
                notes = notes,
                isFavorite = isFavorite,
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId,
                syncStatus = if (bitwardenVaultId != null) "PENDING" else "NONE",
                replicaGroupId = replicaGroupId,
                createdAt = Date(),
                updatedAt = Date(),
                imagePaths = imagePaths
            )
            val newId = keepassSecureItemCreateExecutor.create(
                item = item,
                insertItem = repository::insertItem,
                rollbackItem = repository::deleteItemById
            ) ?: return@launch
            requestBitwardenMutationSync(bitwardenVaultId)
            
            // 记录创建操作
            OperationLogger.logCreate(
                itemType = OperationLogItemType.DOCUMENT,
                itemId = newId,
                itemTitle = title
            )
            onCreated(newId)
        }
    }
    
    // 更新证件
    fun updateDocument(
        id: Long,
        title: String,
        documentData: DocumentData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null,
        replicaGroupId: String? = null
    ) {
        viewModelScope.launch {
            repository.getItemById(id)?.let { existingItem ->
                val keepassIdentity = resolveKeePassMutationIdentity(
                    existingItem = existingItem,
                    targetDatabaseId = keepassDatabaseId,
                    requestedGroupPath = keepassGroupPath
                )
                val oldDocData = parseDocumentData(existingItem.itemData) ?: emptyDocumentData()
                val changes = mutableListOf<FieldChange>()
                fun addChange(fieldName: String, oldValue: Any?, newValue: Any?) {
                    val oldText = oldValue?.toString().orEmpty()
                    val newText = newValue?.toString().orEmpty()
                    if (oldText != newText) {
                        changes.add(FieldChange(fieldName, oldText, newText))
                    }
                }

                addChange("标题", existingItem.title, title)
                addChange("备注", existingItem.notes, notes)
                addChange("证件类型", oldDocData.documentType, documentData.documentType)
                addChange("证件号", oldDocData.documentNumber, documentData.documentNumber)
                addChange("姓名", oldDocData.fullName, documentData.fullName)
                addChange("签发日期", oldDocData.issuedDate, documentData.issuedDate)
                addChange("有效期", oldDocData.expiryDate, documentData.expiryDate)
                addChange("签发机关", oldDocData.issuedBy, documentData.issuedBy)
                addChange("国籍", oldDocData.nationality, documentData.nationality)
                addChange("附加信息", oldDocData.additionalInfo, documentData.additionalInfo)
                addChange("称谓", oldDocData.title, documentData.title)
                addChange("名", oldDocData.firstName, documentData.firstName)
                addChange("中间名", oldDocData.middleName, documentData.middleName)
                addChange("姓", oldDocData.lastName, documentData.lastName)
                addChange("地址 1", oldDocData.address1, documentData.address1)
                addChange("地址 2", oldDocData.address2, documentData.address2)
                addChange("地址 3", oldDocData.address3, documentData.address3)
                addChange("城市", oldDocData.city, documentData.city)
                addChange("省/州", oldDocData.stateProvince, documentData.stateProvince)
                addChange("邮编", oldDocData.postalCode, documentData.postalCode)
                addChange("国家", oldDocData.country, documentData.country)
                addChange("公司", oldDocData.company, documentData.company)
                addChange("邮箱", oldDocData.email, documentData.email)
                addChange("电话", oldDocData.phone, documentData.phone)
                addChange("社保号", oldDocData.ssn, documentData.ssn)
                addChange("用户名", oldDocData.username, documentData.username)
                addChange("护照号", oldDocData.passportNumber, documentData.passportNumber)
                addChange("驾照号", oldDocData.licenseNumber, documentData.licenseNumber)
                addChange("自定义字段", oldDocData.customFields, documentData.customFields)
                
                val updatedItem = existingItem.copy(
                    title = title,
                    itemData = encodeDocumentDataForLocalStorage(documentData),
                    notes = notes,
                    isFavorite = isFavorite,
                    categoryId = categoryId,
                    keepassDatabaseId = keepassDatabaseId,
                    keepassGroupPath = keepassIdentity.groupPath,
                    keepassEntryUuid = keepassIdentity.entryUuid,
                    keepassGroupUuid = keepassIdentity.groupUuid,
                    mdbxDatabaseId = mdbxDatabaseId,
                    mdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null,
                    bitwardenVaultId = bitwardenVaultId,
                    bitwardenFolderId = bitwardenFolderId,
                    replicaGroupId = replicaGroupId ?: existingItem.replicaGroupId,
                    updatedAt = Date(),
                    imagePaths = imagePaths
                )
                val transition = SecureItemBitwardenTransitionResolver.resolve(
                    tag = "DocumentViewModel",
                    existingItem = existingItem,
                    targetVaultId = bitwardenVaultId,
                    targetFolderId = bitwardenFolderId,
                    forcePendingWhenKeepingCipher = bitwardenVaultId != null &&
                        existingItem.bitwardenVaultId == bitwardenVaultId &&
                        existingItem.bitwardenCipherId != null,
                    abortOnQueueFailure = true
                ) { vaultId, cipherId, entryId ->
                    bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = entryId,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
                    )
                } ?: return@launch
                val finalUpdatedItem = updatedItem.copy(
                    bitwardenLocalModified = transition.localModified,
                    bitwardenCipherId = transition.cipherId,
                    bitwardenRevisionDate = transition.revisionDate,
                    syncStatus = transition.syncStatus
                )
                val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
                    existingItem = existingItem,
                    updatedItem = finalUpdatedItem,
                    persistUpdate = { persistedItem ->
                        repository.updateItem(persistedItem)
                    }
                )
                if (keepassSync.isFailure) {
                    Log.e(
                        "DocumentViewModel",
                        "KeePass document update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                    )
                    return@launch
                }
                requestBitwardenMutationSync(bitwardenVaultId)
                
                // 记录更新操作 - 始终记录，即使没有检测到字段变更
                OperationLogger.logUpdate(
                    itemType = OperationLogItemType.DOCUMENT,
                    itemId = id,
                    itemTitle = title,
                    changes = if (changes.isEmpty()) {
                        listOf(
                            FieldChange(
                                "更新",
                                "编辑于",
                                java.text.SimpleDateFormat("HH:mm").format(java.util.Date())
                            )
                        )
                    } else {
                        changes
                    },
                    snapshotChanges = if (changes.isEmpty()) {
                        emptyList()
                    } else {
                        changes + FieldChange(
                            takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA,
                            existingItem.itemData,
                            finalUpdatedItem.itemData
                        )
                    }
                )
            }
        }
    }

    suspend fun moveDocumentToStorage(
        id: Long,
        categoryId: Long?,
        keepassDatabaseId: Long?,
        keepassGroupPath: String?,
        bitwardenVaultId: Long?,
        bitwardenFolderId: String?,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ): Boolean {
        val existingItem = repository.getItemById(id) ?: return false
        val targetMdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null
        val target = when {
            bitwardenVaultId != null -> StorageTarget.Bitwarden(bitwardenVaultId, bitwardenFolderId)
            keepassDatabaseId != null -> StorageTarget.KeePass(keepassDatabaseId, keepassGroupPath)
            mdbxDatabaseId != null -> StorageTarget.Mdbx(mdbxDatabaseId, targetMdbxFolderId)
            else -> StorageTarget.MonicaLocal(categoryId)
        }
        if (hasReplicaTargetConflict(
                itemType = ItemType.DOCUMENT,
                itemId = existingItem.id,
                replicaGroupId = existingItem.replicaGroupId,
                target = target
            )
        ) {
            return false
        }
        val keepassIdentity = resolveKeePassMutationIdentity(
            existingItem = existingItem,
            targetDatabaseId = keepassDatabaseId,
            requestedGroupPath = keepassGroupPath
        )
        val updatedItem = existingItem.copy(
            categoryId = categoryId,
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
        val transition = SecureItemBitwardenTransitionResolver.resolve(
            tag = "DocumentViewModel",
            existingItem = existingItem,
            targetVaultId = bitwardenVaultId,
            targetFolderId = bitwardenFolderId,
            forcePendingWhenKeepingCipher = bitwardenVaultId != null &&
                existingItem.bitwardenVaultId == bitwardenVaultId &&
                existingItem.bitwardenCipherId != null,
            abortOnQueueFailure = true
        ) { vaultId, cipherId, entryId ->
            bitwardenRepository?.queueCipherDelete(
                vaultId = vaultId,
                cipherId = cipherId,
                entryId = entryId,
                itemType = BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
            )
        } ?: return false
        val finalUpdatedItem = updatedItem.copy(
            bitwardenLocalModified = transition.localModified,
            bitwardenCipherId = transition.cipherId,
            bitwardenRevisionDate = transition.revisionDate,
            syncStatus = transition.syncStatus
        )
        val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
            existingItem = existingItem,
            updatedItem = finalUpdatedItem,
            persistUpdate = { persistedItem ->
                repository.updateItem(persistedItem)
            }
        )
        if (keepassSync.isFailure) {
            Log.e(
                "DocumentViewModel",
                "KeePass document move failed before local update: ${keepassSync.exceptionOrNull()?.message}"
            )
            return false
        }
        requestBitwardenMutationSync(bitwardenVaultId)
        return true
    }

    fun saveDocumentAcrossTargets(
        id: Long?,
        title: String,
        documentData: DocumentData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        targets: List<StorageTarget>,
        onPrimaryCreated: suspend (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
            if (distinctTargets.isEmpty()) return@launch

            val existingItem = id?.let { repository.getItemById(it) }?.takeIf { it.itemType == ItemType.DOCUMENT }
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
                        it.itemType == ItemType.DOCUMENT &&
                            it.replicaGroupId == replicaGroupId &&
                            it.id != existingItem.id &&
                            !it.isDeleted
                    }
                    .associateBy { it.toStorageTarget().stableKey }
            } else {
                emptyMap()
            }

            when (currentTarget) {
                is StorageTarget.MonicaLocal -> {
                    if (existingItem == null) {
                        addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = currentTarget.categoryId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateDocument(
                            id = existingItem.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = currentTarget.categoryId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
                is StorageTarget.KeePass -> {
                    if (existingItem == null) {
                        addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = currentTarget.databaseId,
                            keepassGroupPath = currentTarget.groupPath,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateDocument(
                            id = existingItem.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = currentTarget.databaseId,
                            keepassGroupPath = currentTarget.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
                is StorageTarget.Mdbx -> {
                    if (existingItem == null) {
                        addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = currentTarget.databaseId,
                            mdbxFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateDocument(
                            id = existingItem.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = currentTarget.databaseId,
                            mdbxFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
                is StorageTarget.Bitwarden -> {
                    if (existingItem == null) {
                        addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = currentTarget.vaultId,
                            bitwardenFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateDocument(
                            id = existingItem.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = currentTarget.vaultId,
                            bitwardenFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
            }

            distinctTargets
                .filter { it.stableKey != currentTarget.stableKey }
                .forEach { target ->
                    val existingReplica = existingReplicasByKey[target.stableKey]
                    when (target) {
                        is StorageTarget.MonicaLocal -> if (existingReplica == null) addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = target.categoryId,
                            replicaGroupId = replicaGroupId
                        ) else updateDocument(
                            id = existingReplica.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = target.categoryId,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.KeePass -> if (existingReplica == null) addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        ) else updateDocument(
                            id = existingReplica.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.Mdbx -> if (existingReplica == null) addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = target.databaseId,
                            mdbxFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        ) else updateDocument(
                            id = existingReplica.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = target.databaseId,
                            mdbxFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.Bitwarden -> if (existingReplica == null) addDocument(
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        ) else updateDocument(
                            id = existingReplica.id,
                            title = title,
                            documentData = documentData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }

            if (existingItem != null && distinctTargets.size == 1 &&
                currentTarget.stableKey != existingItem.toStorageTarget().stableKey) {
                repository.getAllItems().first()
                .filter {
                    it.itemType == ItemType.DOCUMENT &&
                        it.replicaGroupId == replicaGroupId &&
                        it.id != existingItem?.id &&
                        !it.isDeleted &&
                        it.toStorageTarget().stableKey !in selectedTargetKeys
                }
                .forEach { repository.deleteItemById(it.id) }
            }
        }
    }

    suspend fun copyDocumentToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Long? {
        if (item.itemType != ItemType.DOCUMENT || item.hasOwnershipConflict()) return null
        val localCopy = item.asMonicaLocalCopy(categoryId).copy(
            createdAt = Date(),
            updatedAt = Date()
        )
        val newId = repository.insertItem(localCopy)
        return runCatching {
            attachmentFacade?.cloneAttachmentsToNewOwner(
                sourceOwner = AttachmentOwner.secureItem(item.id),
                targetOwner = AttachmentOwner.secureItem(newId),
                sourceBitwardenContext = attachmentBitwardenContextFor(item),
                sourceKeepassContext = attachmentKeepassContextFor(item),
                excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.DOCUMENT)
            )
            newId
        }.getOrElse { error ->
            Log.e("DocumentViewModel", "Document attachment clone failed: ${error.message}", error)
            runCatching { repository.deleteItemById(newId) }
            null
        }
    }

    private suspend fun attachmentBitwardenContextFor(
        item: SecureItem
    ): AttachmentFacade.BitwardenContext? {
        val vaultId = item.bitwardenVaultId ?: return null
        val vault = bitwardenRepository?.getAllVaultsFlow()?.first()?.firstOrNull { it.id == vaultId }
            ?: return null
        return bitwardenRepository?.getAttachmentBitwardenContext(vault, item.bitwardenCipherId)
    }

    private fun attachmentKeepassContextFor(item: SecureItem): AttachmentFacade.KeePassContext? {
        val databaseId = item.keepassDatabaseId
        val entryUuid = item.keepassEntryUuid?.takeIf { it.isNotBlank() }
        return if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId, entryUuid)
        } else {
            null
        }
    }

    suspend fun moveDocumentToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Result<Long> {
        if (item.itemType != ItemType.DOCUMENT) {
            return Result.failure(IllegalArgumentException("仅支持证件项目"))
        }
        if (item.hasOwnershipConflict()) {
            return Result.failure(IllegalStateException("证件来源冲突，无法移动到 Monica 本地"))
        }

        val newId = copyDocumentToMonicaLocal(item, categoryId)
            ?: return Result.failure(IllegalStateException("创建 Monica 本地证件副本失败"))

        val sourceDelete = when (val ownership = item.resolveOwnership()) {
            is SecureItemOwnership.Bitwarden -> {
                val vaultId = ownership.vaultId
                val cipherId = ownership.cipherId
                if (vaultId == null || cipherId.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Bitwarden 证件缺少同步标识"))
                } else {
                    bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
                    ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
                }
            }
            is SecureItemOwnership.KeePass -> {
                if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("KeePass 证件源删除失败"))
                }
            }
            is SecureItemOwnership.MonicaLocal -> Result.success(Unit)
            is SecureItemOwnership.Mdbx -> Result.success(Unit)
            is SecureItemOwnership.Conflict -> Result.failure(IllegalStateException("证件来源冲突，无法移动到 Monica 本地"))
        }

        if (sourceDelete.isFailure) {
            Log.e(
                "DocumentViewModel",
                "Document move to Monica local kept target copy after source cleanup failed; sourceId=${item.id} targetId=$newId error=${sourceDelete.exceptionOrNull()?.message}"
            )
            return Result.failure(
                sourceDelete.exceptionOrNull() ?: IllegalStateException("删除证件源失败")
            )
        }

        repository.deleteItem(item)
        return Result.success(newId)
    }

    private suspend fun hasReplicaTargetConflict(
        itemType: ItemType,
        itemId: Long,
        replicaGroupId: String?,
        target: StorageTarget
    ): Boolean {
        if (replicaGroupId.isNullOrBlank()) return false
        return repository.getAllItems().first()
            .asSequence()
            .filter { candidate ->
                candidate.itemType == itemType &&
                    candidate.id != itemId &&
                    candidate.replicaGroupId == replicaGroupId &&
                    !candidate.isDeleted
            }
            .any { candidate -> candidate.toStorageTarget().stableKey == target.stableKey }
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
    
    // 删除证件
    // @param softDelete 是否软删除（移入回收站），默认为 true
    fun deleteDocument(id: Long, softDelete: Boolean = true) {
        viewModelScope.launch {
            repository.getItemById(id)?.let { item ->
                val vaultId = item.bitwardenVaultId
                val cipherId = item.bitwardenCipherId
                val isBitwardenCipher = vaultId != null && !cipherId.isNullOrBlank()

                if (isBitwardenCipher) {
                    val queueResult = bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId!!,
                        cipherId = cipherId!!,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_DOCUMENT
                    )
                    if (queueResult?.isFailure == true) {
                        Log.e("DocumentViewModel", "Queue Bitwarden delete failed: ${queueResult.exceptionOrNull()?.message}")
                        return@launch
                    }
                }

                if (!softDelete || isBitwardenCipher) {
                    if (!keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = softDelete || isBitwardenCipher)) {
                        Log.e("DocumentViewModel", "KeePass delete failed for document id=${item.id}")
                        return@launch
                    }
                }

                if (isBitwardenCipher) {
                    val softDeletedItem = item.copy(
                        isDeleted = true,
                        deletedAt = Date(),
                        updatedAt = Date(),
                        bitwardenLocalModified = true
                    )
                    repository.updateItem(softDeletedItem)
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.DOCUMENT,
                        itemId = id,
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
                        itemType = OperationLogItemType.DOCUMENT,
                        itemId = id,
                        itemTitle = item.title,
                        detail = "移入回收站"
                    )

                    if (!isBitwardenCipher && item.keepassDatabaseId != null) {
                        viewModelScope.launch keepassDeleteSync@{
                            if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = true)) {
                                Log.i("DocumentViewModel", "KeePass trash delete synced for document id=${item.id}")
                                return@keepassDeleteSync
                            }

                            Log.e("DocumentViewModel", "KeePass trash delete failed, reverting local trash state for document id=${item.id}")
                            repository.updateItem(item.copy(updatedAt = Date()))
                        }
                    }
                } else {
                    // 永久删除
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.DOCUMENT,
                        itemId = id,
                        itemTitle = item.title
                    )
                    repository.deleteItem(item)
                }
            }
        }
    }
    
    // 切换收藏状态
    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            repository.getItemById(id)?.let { item ->
                repository.updateItem(item.copy(
                    isFavorite = !item.isFavorite,
                    updatedAt = Date()
                ))
            }
        }
    }
    
    // 更新排序顺序
    fun updateSortOrders(items: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateSortOrders(items)
        }
    }
    
    // 搜索证件
    fun searchDocuments(query: String): Flow<List<SecureItem>> {
        return repository.searchItems(query)
    }
    
    // 解析证件数据
    fun parseDocumentData(jsonData: String): DocumentData? {
        return CardWalletDataCodec.parseDocumentData(
            raw = jsonData,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        )
    }

    private fun emptyDocumentData() = DocumentData(
        documentNumber = "",
        documentType = takagi.ru.monica.data.model.DocumentType.ID_CARD,
        fullName = "",
        issuedDate = "",
        expiryDate = ""
    )

    private suspend fun repairLegacyDetachedKeePassItems() {
        repository.repairLegacyDetachedKeePassItems(::hasKeePassDatabase)
    }

    private suspend fun hasKeePassDatabase(databaseId: Long): Boolean {
        return localKeePassDatabaseDao?.getDatabaseById(databaseId) != null
    }
}

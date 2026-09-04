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
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.CardType
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

data class ParsedBankCardItem(
    val item: SecureItem,
    val cardData: BankCardData
)

class BankCardViewModel(
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

    private fun encodeCardDataForLocalStorage(cardData: BankCardData): String {
        return encodeStoredSensitiveValueForNewWrite(
            CardWalletDataCodec.encodeBankCardData(cardData)
        )
    }

    private fun encodeIncomingItemDataForLocalStorage(itemData: String): String {
        return encodeStoredSensitiveValueForNewWrite(decryptStoredSensitiveValue(itemData))
    }

    init {
        // Legacy binding repair scans the shared secure-item table. Keep it
        // off the main dispatcher so it cannot delay the first card frame.
        viewModelScope.launch(Dispatchers.Default) {
            repairLegacyDetachedKeePassItems()
        }
    }

    fun syncAllKeePassCards() {
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = SyncDiagnostics.nextTaskId("kp-card-all"),
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = null,
                        itemTypes = setOf(SyncItemKind.BANK_CARD)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncAllKeePassCardsNow()
            }
        }
    }

    suspend fun syncAllKeePassCardsNow() {
        val taskId = SyncDiagnostics.nextTaskId("kp-card-all")
        val target = "keepass_compat:bank_card:all"
        val trigger = "CARD_WALLET_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            val dao = localKeePassDatabaseDao ?: run {
                SyncDiagnostics.skipped(taskId, target, trigger, "dao_unavailable", startedAt)
                return
            }
            val dbs = withContext(Dispatchers.IO) { dao.getAllDatabasesSync() }
            dbs.forEach { syncKeePassCardsNow(it.id) }
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

    fun syncKeePassCards(databaseId: Long) {
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = SyncDiagnostics.nextTaskId("kp-card"),
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.BANK_CARD)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncKeePassCardsNow(databaseId)
            }
        }
    }

    suspend fun syncKeePassCardsNow(databaseId: Long) {
        val taskId = SyncDiagnostics.nextTaskId("kp-card")
        val target = "keepass_compat:bank_card:$databaseId"
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
                takagi.ru.monica.keepass.KeePassProjectionKind.BANK_CARD
            ).getOrThrow()
            if (!decision.needsRefresh) {
                SyncDiagnostics.skipped(taskId, target, trigger, "native_revision_unchanged", startedAt)
                return
            }
            val snapshots = bridge
                .readLegacySecureItems(databaseId, setOf(ItemType.BANK_CARD))
                ?.getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "bridge_or_read_unavailable", startedAt)
                    return
                }

            val existingCards = repository.getItemsByType(ItemType.BANK_CARD).first()
            snapshots.forEach { snapshot ->
                val incoming = snapshot.item
                val existingByUuid = incoming.keepassEntryUuid
                    ?.takeIf { it.isNotBlank() }
                    ?.let { repository.getItemByKeePassUuid(databaseId, it) }
                val existingBySource = snapshot.sourceMonicaId
                    ?.takeIf { it > 0 }
                    ?.let { sourceId -> repository.getItemById(sourceId) }
                    ?.takeIf { it.itemType == ItemType.BANK_CARD }

                val existing = existingByUuid ?: existingBySource ?: existingCards.firstOrNull {
                    it.itemType == ItemType.BANK_CARD &&
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
                setOf(takagi.ru.monica.keepass.KeePassProjectionKind.BANK_CARD)
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
    
    private val cardListSharingStarted = SharingStarted.WhileSubscribed(5000)
    private val allCardsSource: SharedFlow<List<SecureItem>> =
        repository.getItemsByType(ItemType.BANK_CARD)
            .shareIn(
                scope = viewModelScope,
                started = cardListSharingStarted,
                replay = 1,
            )

    // 获取所有银行卡
    val allCards: StateFlow<List<SecureItem>> = allCardsSource
        .stateIn(
            scope = viewModelScope,
            started = cardListSharingStarted,
            initialValue = emptyList()
        )

    private val parsedCardsStateSource: Flow<LoadedListState<ParsedBankCardItem>> = allCardsSource
        .map { items ->
            LoadedListState(
                items = items.map { item ->
                    ParsedBankCardItem(
                        item = item,
                        cardData = parseCardData(item.itemData) ?: emptyBankCardData()
                    )
                },
                isReady = true,
            )
        }
        .flowOn(Dispatchers.Default)

    val parsedCardsState: StateFlow<LoadedListState<ParsedBankCardItem>> = parsedCardsStateSource
        .stateIn(
            scope = viewModelScope,
            started = cardListSharingStarted,
            initialValue = LoadedListState(),
        )

    val parsedCardsReady: StateFlow<Boolean> = parsedCardsState
        .map { state -> state.isReady }
        .stateIn(
            scope = viewModelScope,
            started = cardListSharingStarted,
            initialValue = false,
        )

    val isLoading: StateFlow<Boolean> = parsedCardsReady
        .map { ready -> !ready }
        .stateIn(
            scope = viewModelScope,
            started = cardListSharingStarted,
            initialValue = true,
        )

    val parsedCards: StateFlow<List<ParsedBankCardItem>> = parsedCardsState
        .map { state -> state.items }
        .stateIn(
            scope = viewModelScope,
            started = cardListSharingStarted,
            initialValue = emptyList()
        )
    
    // 根据ID获取银行卡
    suspend fun getCardById(id: Long): SecureItem? {
        val item = repository.getItemById(id) ?: return null
        return repository.normalizeLegacyDetachedKeePassItem(item, ::hasKeePassDatabase)
    }
    
    /**
     * 快速添加银行卡（从底部导航栏快速添加）
     */
    fun quickAddBankCard(name: String, cardNumber: String) {
        if (name.isBlank()) return
        val cardData = BankCardData(
            cardNumber = cardNumber,
            cardholderName = "",
            expiryMonth = "",
            expiryYear = "",
            cvv = "",
            bankName = name,
            cardType = CardType.CREDIT
        )
        addCard(title = name, cardData = cardData)
    }
    
    // 添加银行卡
    fun addCard(
        title: String,
        cardData: BankCardData,
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
                itemType = ItemType.BANK_CARD,
                title = title,
                itemData = encodeCardDataForLocalStorage(cardData),
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
                itemType = OperationLogItemType.BANK_CARD,
                itemId = newId,
                itemTitle = title
            )
            onCreated(newId)
        }
    }
    
    // 更新银行卡
    fun updateCard(
        id: Long,
        title: String,
        cardData: BankCardData,
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
                val oldCardData = parseCardData(existingItem.itemData) ?: emptyBankCardData()
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
                addChange("卡号", oldCardData.cardNumber, cardData.cardNumber)
                addChange("持卡人", oldCardData.cardholderName, cardData.cardholderName)
                addChange("有效期月份", oldCardData.expiryMonth, cardData.expiryMonth)
                addChange("有效期年份", oldCardData.expiryYear, cardData.expiryYear)
                addChange("CVV", oldCardData.cvv, cardData.cvv)
                addChange("银行", oldCardData.bankName, cardData.bankName)
                addChange("卡类型", oldCardData.cardType, cardData.cardType)
                addChange("账单地址", oldCardData.billingAddress, cardData.billingAddress)
                addChange("卡组织", oldCardData.brand, cardData.brand)
                addChange("卡片昵称", oldCardData.nickname, cardData.nickname)
                addChange("起始月份", oldCardData.validFromMonth, cardData.validFromMonth)
                addChange("起始年份", oldCardData.validFromYear, cardData.validFromYear)
                addChange("PIN", oldCardData.pin, cardData.pin)
                addChange("IBAN", oldCardData.iban, cardData.iban)
                addChange("SWIFT/BIC", oldCardData.swiftBic, cardData.swiftBic)
                addChange("路由号码", oldCardData.routingNumber, cardData.routingNumber)
                addChange("账户号码", oldCardData.accountNumber, cardData.accountNumber)
                addChange("分行代码", oldCardData.branchCode, cardData.branchCode)
                addChange("币种", oldCardData.currency, cardData.currency)
                addChange("客服电话", oldCardData.customerServicePhone, cardData.customerServicePhone)
                addChange("自定义字段", oldCardData.customFields, cardData.customFields)
                
                val updatedItem = existingItem.copy(
                    title = title,
                    itemData = encodeCardDataForLocalStorage(cardData),
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
                    tag = "BankCardViewModel",
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
                        itemType = BitwardenPendingOperation.ITEM_TYPE_CARD
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
                        "BankCardViewModel",
                        "KeePass bank card update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                    )
                    return@launch
                }
                requestBitwardenMutationSync(bitwardenVaultId)
                
                // 记录更新操作 - 始终记录，即使没有检测到字段变更
                OperationLogger.logUpdate(
                    itemType = OperationLogItemType.BANK_CARD,
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

    suspend fun moveCardToStorage(
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
                itemType = ItemType.BANK_CARD,
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
            tag = "BankCardViewModel",
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
                itemType = BitwardenPendingOperation.ITEM_TYPE_CARD
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
                "BankCardViewModel",
                "KeePass bank card move failed before local update: ${keepassSync.exceptionOrNull()?.message}"
            )
            return false
        }
        requestBitwardenMutationSync(bitwardenVaultId)
        return true
    }

    fun saveCardAcrossTargets(
        id: Long?,
        title: String,
        cardData: BankCardData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        targets: List<StorageTarget>,
        onPrimaryCreated: suspend (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
            if (distinctTargets.isEmpty()) return@launch

            val existingItem = id?.let { repository.getItemById(it) }?.takeIf { it.itemType == ItemType.BANK_CARD }
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
                        it.itemType == ItemType.BANK_CARD &&
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
                        addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = currentTarget.categoryId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateCard(
                            id = existingItem.id,
                            title = title,
                            cardData = cardData,
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
                        addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = currentTarget.databaseId,
                            keepassGroupPath = currentTarget.groupPath,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateCard(
                            id = existingItem.id,
                            title = title,
                            cardData = cardData,
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
                        addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = currentTarget.databaseId,
                            mdbxFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateCard(
                            id = existingItem.id,
                            title = title,
                            cardData = cardData,
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
                        addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = currentTarget.vaultId,
                            bitwardenFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId,
                            onCreated = onPrimaryCreated
                        )
                    } else {
                        updateCard(
                            id = existingItem.id,
                            title = title,
                            cardData = cardData,
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
                        is StorageTarget.MonicaLocal -> if (existingReplica == null) addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = target.categoryId,
                            replicaGroupId = replicaGroupId
                        ) else updateCard(
                            id = existingReplica.id,
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            categoryId = target.categoryId,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.KeePass -> if (existingReplica == null) addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        ) else updateCard(
                            id = existingReplica.id,
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.Bitwarden -> if (existingReplica == null) addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        ) else updateCard(
                            id = existingReplica.id,
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.Mdbx -> if (existingReplica == null) addCard(
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = target.databaseId,
                            mdbxFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        ) else updateCard(
                            id = existingReplica.id,
                            title = title,
                            cardData = cardData,
                            notes = notes,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            mdbxDatabaseId = target.databaseId,
                            mdbxFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }

            if (existingItem != null && distinctTargets.size == 1 &&
                currentTarget.stableKey != existingItem.toStorageTarget().stableKey) {
                repository.getAllItems().first()
                .filter {
                    it.itemType == ItemType.BANK_CARD &&
                        it.replicaGroupId == replicaGroupId &&
                        it.id != existingItem?.id &&
                        !it.isDeleted &&
                        it.toStorageTarget().stableKey !in selectedTargetKeys
                }
                .forEach { repository.deleteItemById(it.id) }
            }
        }
    }

    suspend fun copyCardToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Long? {
        if (item.itemType != ItemType.BANK_CARD || item.hasOwnershipConflict()) return null
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
                excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.BANK_CARD)
            )
            newId
        }.getOrElse { error ->
            Log.e("BankCardViewModel", "Bank card attachment clone failed: ${error.message}", error)
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

    suspend fun moveCardToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Result<Long> {
        if (item.itemType != ItemType.BANK_CARD) {
            return Result.failure(IllegalArgumentException("仅支持银行卡项目"))
        }
        if (item.hasOwnershipConflict()) {
            return Result.failure(IllegalStateException("银行卡来源冲突，无法移动到 Monica 本地"))
        }

        val newId = copyCardToMonicaLocal(item, categoryId)
            ?: return Result.failure(IllegalStateException("创建 Monica 本地银行卡副本失败"))

        val sourceDelete = when (val ownership = item.resolveOwnership()) {
            is SecureItemOwnership.Bitwarden -> {
                val vaultId = ownership.vaultId
                val cipherId = ownership.cipherId
                if (vaultId == null || cipherId.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Bitwarden 银行卡缺少同步标识"))
                } else {
                    bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_CARD
                    ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
                }
            }
            is SecureItemOwnership.KeePass -> {
                if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("KeePass 银行卡源删除失败"))
                }
            }
            is SecureItemOwnership.MonicaLocal -> Result.success(Unit)
            is SecureItemOwnership.Mdbx -> Result.success(Unit)
            is SecureItemOwnership.Conflict -> Result.failure(IllegalStateException("银行卡来源冲突，无法移动到 Monica 本地"))
        }

        if (sourceDelete.isFailure) {
            Log.e(
                "BankCardViewModel",
                "Bank card move to Monica local kept target copy after source cleanup failed; sourceId=${item.id} targetId=$newId error=${sourceDelete.exceptionOrNull()?.message}"
            )
            return Result.failure(
                sourceDelete.exceptionOrNull() ?: IllegalStateException("删除银行卡源失败")
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
    
    // 删除银行卡
    // @param softDelete 是否软删除（移入回收站），默认为 true
    fun deleteCard(id: Long, softDelete: Boolean = true) {
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
                        itemType = BitwardenPendingOperation.ITEM_TYPE_CARD
                    )
                    if (queueResult?.isFailure == true) {
                        Log.e("BankCardViewModel", "Queue Bitwarden delete failed: ${queueResult.exceptionOrNull()?.message}")
                        return@launch
                    }
                }

                if (!softDelete || isBitwardenCipher) {
                    if (!keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = softDelete || isBitwardenCipher)) {
                        Log.e("BankCardViewModel", "KeePass delete failed for card id=${item.id}")
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
                        itemType = OperationLogItemType.BANK_CARD,
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
                        itemType = OperationLogItemType.BANK_CARD,
                        itemId = id,
                        itemTitle = item.title,
                        detail = "移入回收站"
                    )

                    if (!isBitwardenCipher && item.keepassDatabaseId != null) {
                        viewModelScope.launch keepassDeleteSync@{
                            if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = true)) {
                                Log.i("BankCardViewModel", "KeePass trash delete synced for card id=${item.id}")
                                return@keepassDeleteSync
                            }

                            Log.e("BankCardViewModel", "KeePass trash delete failed, reverting local trash state for card id=${item.id}")
                            repository.updateItem(item.copy(updatedAt = Date()))
                        }
                    }
                } else {
                    // 永久删除
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.BANK_CARD,
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
    
    // 搜索银行卡
    fun searchCards(query: String): Flow<List<SecureItem>> {
        return repository.searchItems(query)
    }
    
    // 解析银行卡数据
    fun parseCardData(jsonData: String): BankCardData? {
        return CardWalletDataCodec.parseBankCardData(
            raw = jsonData,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        )
    }

    private fun emptyBankCardData() = BankCardData(
        cardNumber = "",
        cardholderName = "",
        expiryMonth = "",
        expiryYear = ""
    )

    private suspend fun repairLegacyDetachedKeePassItems() {
        repository.repairLegacyDetachedKeePassItems(::hasKeePassDatabase)
    }

    private suspend fun hasKeePassDatabase(databaseId: Long): Boolean {
        return localKeePassDatabaseDao?.getDatabaseById(databaseId) != null
    }
}

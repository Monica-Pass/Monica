package takagi.ru.monica.repository

import android.util.Log
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.repository.AttachmentRepository
import takagi.ru.monica.bitwarden.BitwardenMutationStateHelper
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.data.model.CardWalletDataCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import takagi.ru.monica.util.TotpDataResolver
import java.net.URLDecoder
import java.util.Date
import java.util.Locale

/**
 * Repository for secure items (TOTP, Bank Cards, Documents)
 */
class SecureItemRepository(
    private val secureItemDao: SecureItemDao,
    private val mdbxRepository: MdbxRepository? = null,
    private val decryptSensitiveValue: ((String) -> String)? = null,
    private val attachmentRepository: AttachmentRepository? = null
) {
    companion object {
        private const val TAG = "SecureItemRepository"
    }

    private val json = Json { ignoreUnknownKeys = true }
    // MainActivity creates several secure-item ViewModels at once. They all
    // used to scan the complete secure_items table independently during init,
    // competing for the same Room executor and delaying the first visible
    // list. Keep the one-time legacy repair single-flight per repository.
    private val legacyRepairMutex = Mutex()
    @Volatile
    private var legacyRepairCompleted = false

    private data class TotpFingerprint(
        val issuer: String,
        val accountName: String,
        val secret: String
    )

    private fun SecureItem.mdbxReplicaPrefix(): String = when (itemType) {
        ItemType.NOTE -> "note"
        ItemType.TOTP -> "totp"
        ItemType.BANK_CARD -> "card"
        ItemType.DOCUMENT -> "document-ref"
        ItemType.BILLING_ADDRESS -> "billing-address"
        ItemType.PAYMENT_ACCOUNT -> "payment-account"
        ItemType.PASSWORD -> "password"
    }

    private fun SecureItem.mdbxObjectId(id: Long = this.id): String? {
        val stableId = id.takeIf { it > 0 } ?: this.id.takeIf { it > 0 } ?: return replicaGroupId
        return replicaGroupId
            ?.takeIf { it.startsWith("${mdbxReplicaPrefix()}:") }
            ?: "${mdbxReplicaPrefix()}:$stableId"
    }

    
    fun getAllItems(): Flow<List<SecureItem>> {
        return secureItemDao.getAllItems()
    }

    suspend fun getAllLocalItems(): List<SecureItem> {
        return ItemType.entries.flatMap { type ->
            if (type == ItemType.PASSWORD) {
                emptyList()
            } else {
                secureItemDao.getActiveLocalItemsByTypeSync(type)
            }
        }
    }

    suspend fun getDeletedItemsSync(): List<SecureItem> = secureItemDao.getDeletedItemsSync()
    
    fun getItemsByType(type: ItemType): Flow<List<SecureItem>> {
        return secureItemDao.getItemsByType(type)
    }
    
    fun searchItems(query: String): Flow<List<SecureItem>> {
        return secureItemDao.searchItems(query)
    }
    
    fun searchItemsByType(type: ItemType, query: String): Flow<List<SecureItem>> {
        return secureItemDao.searchItemsByType(type, query)
    }
    
    fun getFavoriteItems(): Flow<List<SecureItem>> {
        return secureItemDao.getFavoriteItems()
    }
    
    suspend fun getItemById(id: Long): SecureItem? {
        return secureItemDao.getItemById(id)
    }

    suspend fun ensureMdbxCopyForBinding(
        source: SecureItem,
        databaseId: Long,
        title: String = source.title,
        notes: String = source.notes,
        itemData: String = source.itemData,
        imagePaths: String = source.imagePaths,
        isFavorite: Boolean = source.isFavorite,
        categoryId: Long? = source.categoryId,
        mdbxFolderId: String? = source.mdbxFolderId
    ): SecureItem {
        if (source.mdbxDatabaseId == databaseId) {
            val updated = source.copy(
                title = title,
                notes = notes,
                itemData = itemData,
                imagePaths = imagePaths,
                isFavorite = isFavorite,
                categoryId = categoryId,
                mdbxFolderId = mdbxFolderId,
                updatedAt = Date()
            )
            updateItem(updated)
            return secureItemDao.getItemById(source.id) ?: updated
        }

        val prefix = source.mdbxReplicaPrefix()
        val replicaId = source.replicaGroupId
            ?.takeIf { it.startsWith("$prefix:") }
            ?: "$prefix:local:${source.id}"
        val existingCopy = secureItemDao.getActiveItemsByTypeSync(source.itemType)
            .firstOrNull { item ->
                item.mdbxDatabaseId == databaseId &&
                    item.replicaGroupId == replicaId &&
                    !item.isDeleted
            }

        val now = Date()
        val copy = (existingCopy ?: source).copy(
            id = existingCopy?.id ?: 0L,
            title = title,
            notes = notes,
            itemData = itemData,
            imagePaths = imagePaths,
            isFavorite = isFavorite,
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = databaseId,
            mdbxFolderId = mdbxFolderId,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "NONE",
            replicaGroupId = replicaId,
            isDeleted = false,
            deletedAt = null,
            createdAt = existingCopy?.createdAt ?: now,
            updatedAt = now
        )

        val copyId = if (existingCopy != null) {
            updateItem(copy)
            existingCopy.id
        } else {
            insertItem(copy)
        }
        return secureItemDao.getItemById(copyId) ?: copy.copy(id = copyId)
    }

    suspend fun normalizeLegacyDetachedKeePassItem(
        item: SecureItem,
        databaseExists: suspend (Long) -> Boolean = { false }
    ): SecureItem {
        if (!isLegacyDetachedKeePassItem(item, databaseExists)) return item
        secureItemDao.clearKeePassBindingForIds(listOf(item.id))
        return secureItemDao.getItemById(item.id) ?: item.copy(
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null
        )
    }

    suspend fun getItemByKeePassUuid(databaseId: Long, entryUuid: String): SecureItem? {
        return secureItemDao.findByKeePassEntryUuid(databaseId, entryUuid)
    }

    fun observeItemById(id: Long): Flow<SecureItem?> {
        return secureItemDao.observeItemById(id)
    }

    suspend fun insertItems(items: List<SecureItem>): List<Long> {
        if (items.isEmpty()) return emptyList()
        require(items.all { it.id == 0L }) { "Batch insert only accepts new secure items" }

        data class InsertedBatch(
            val ids: List<Long>,
            val items: List<SecureItem>
        )

        return commitRoomThenMirror(
            roomCommit = {
                val persistedItems = secureItemDao.insertItems(items)
                check(persistedItems.size == items.size) {
                    "Secure item batch insert returned an incomplete item list"
                }
                InsertedBatch(ids = persistedItems.map(SecureItem::id), items = persistedItems)
            },
            mirrorCommit = { batch ->
                mdbxRepository?.upsertSecureItems(batch.items)
            },
            rollbackRoom = { batch ->
                secureItemDao.deleteItemsByIds(batch.ids)
            },
            rollbackMirror = { batch ->
                mdbxRepository?.deleteSecureItems(batch.items)
            }
        ).ids
    }
    
    suspend fun insertItem(item: SecureItem): Long {
        return commitRoomThenMirror(
            roomCommit = {
                val id = secureItemDao.insertItem(item)
                val persistedItem = item.copy(
                    id = id,
                    replicaGroupId = if (item.mdbxDatabaseId != null) item.mdbxObjectId(id) else item.replicaGroupId
                )
                if (persistedItem.replicaGroupId != item.replicaGroupId) {
                    secureItemDao.updateItem(persistedItem)
                }
                id to persistedItem
            },
            mirrorCommit = { (_, persistedItem) ->
                mdbxRepository?.upsertSecureItem(persistedItem)
            },
            rollbackRoom = { (id, _) -> secureItemDao.deleteItemById(id) },
            rollbackMirror = { (_, persistedItem) ->
                mdbxRepository?.deleteSecureItem(persistedItem)
            }
        ).first
    }
    
    suspend fun updateItem(item: SecureItem) {
        val existingItem = if (item.id != 0L) secureItemDao.getItemById(item.id) else null
        val normalizedItem = BitwardenMutationStateHelper.normalizeSecureItemUpdate(existingItem, item)
        commitMirrorThenRoom(
            mirrorCommit = {
                if (normalizedItem.mdbxDatabaseId != null) {
                    mdbxRepository?.upsertSecureItem(normalizedItem)
                }
                if (
                    existingItem?.mdbxDatabaseId != null &&
                    existingItem.mdbxDatabaseId != normalizedItem.mdbxDatabaseId
                ) {
                    mdbxRepository?.deleteSecureItem(existingItem)
                }
            },
            roomCommit = { secureItemDao.updateItem(normalizedItem) },
            rollbackMirror = {
                if (normalizedItem.mdbxDatabaseId != null) {
                    mdbxRepository?.deleteSecureItem(normalizedItem)
                }
                if (existingItem?.mdbxDatabaseId != null) {
                    mdbxRepository?.upsertSecureItem(existingItem)
                }
            }
        )
    }

    suspend fun updateItems(items: List<SecureItem>) {
        if (items.isEmpty()) return
        require(items.all { it.id > 0L }) { "Batch update requires persisted secure items" }
        require(items.map(SecureItem::id).distinct().size == items.size) {
            "Batch update contains duplicate secure item IDs"
        }

        val existingById = secureItemDao.getItemsByIds(items.map(SecureItem::id)).associateBy(SecureItem::id)
        check(existingById.size == items.size) { "Batch update contains missing secure items" }
        val normalizedItems = items.map { item ->
            BitwardenMutationStateHelper.normalizeSecureItemUpdate(existingById.getValue(item.id), item)
        }
        val previousMdbxItems = items.map { existingById.getValue(it.id) }
            .filter { it.mdbxDatabaseId != null }
        val movedFromOtherMdbxDatabases = normalizedItems.mapNotNull { normalized ->
            existingById.getValue(normalized.id).takeIf { existing ->
                existing.mdbxDatabaseId != null &&
                    existing.mdbxDatabaseId != normalized.mdbxDatabaseId
            }
        }

        commitMirrorThenRoom(
            mirrorCommit = {
                mdbxRepository?.upsertSecureItems(normalizedItems.filter { it.mdbxDatabaseId != null })
                mdbxRepository?.deleteSecureItems(movedFromOtherMdbxDatabases)
            },
            roomCommit = { secureItemDao.updateItems(normalizedItems) },
            rollbackMirror = {
                mdbxRepository?.deleteSecureItems(normalizedItems.filter { it.mdbxDatabaseId != null })
                mdbxRepository?.upsertSecureItems(previousMdbxItems)
            }
        )
    }
    
    suspend fun deleteItem(item: SecureItem) {
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deleteSecureItem(item) },
            roomCommit = { secureItemDao.deleteItem(item) },
            rollbackMirror = { mdbxRepository?.upsertSecureItem(item) }
        )
    }

    suspend fun deleteItems(items: List<SecureItem>) {
        if (items.isEmpty()) return
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deleteSecureItems(items) },
            roomCommit = { secureItemDao.deleteItemsByIds(items.map(SecureItem::id)) },
            rollbackMirror = { mdbxRepository?.upsertSecureItems(items) }
        )
    }
    
    suspend fun deleteItemById(id: Long) {
        val item = secureItemDao.getItemById(id)
        commitMirrorThenRoom(
            mirrorCommit = { item?.let { mdbxRepository?.deleteSecureItem(it) } },
            roomCommit = { secureItemDao.deleteItemById(id) },
            rollbackMirror = { item?.let { mdbxRepository?.upsertSecureItem(it) } }
        )
    }
    
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) {
        val existingItem = secureItemDao.getItemById(id) ?: return
        updateItem(
            existingItem.copy(
                isFavorite = isFavorite,
                updatedAt = java.util.Date()
            )
        )
    }
    
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        updateFavoriteStatus(id, isFavorite)
    }
    
    suspend fun updateSortOrder(id: Long, sortOrder: Int) {
        updateSortOrders(listOf(id to sortOrder))
    }
    
    suspend fun updateSortOrders(items: List<Pair<Long, Int>>) {
        if (items.isEmpty()) return
        val orderById = items.toMap()
        val previousItems = secureItemDao.getItemsByIds(orderById.keys.toList())
        val reorderedItems = previousItems.mapNotNull { item ->
            orderById[item.id]?.let { sortOrder -> item.copy(sortOrder = sortOrder) }
        }
        commitMirrorThenRoom(
            mirrorCommit = {
                mirrorSortOrderItems(reorderedItems)
            },
            roomCommit = { secureItemDao.updateSortOrders(items) },
            rollbackMirror = {
                mirrorSortOrderItems(previousItems)
            }
        )
    }

    /**
     * Sorting should remain usable even when Room still contains an item whose
     * old MDBX database was removed. A stale replica must not turn a harmless
     * UI reorder into an uncaught coroutine exception; valid databases still
     * receive their updates independently.
     */
    private suspend fun mirrorSortOrderItems(items: List<SecureItem>) {
        val repository = mdbxRepository ?: return
        items
            .filter { it.mdbxDatabaseId != null }
            .groupBy { it.mdbxDatabaseId }
            .values
            .forEach { group ->
                try {
                    repository.upsertSecureItems(group)
                } catch (error: MdbxVaultNotFoundException) {
                    Log.w(TAG, "Skipping sort mirror for removed MDBX database ${error.databaseId}")
                }
            }
    }
    
    /**
     * 检查是否存在重复的安全项（基于 title 匹配，旧方法保留兼容）
     */
    suspend fun isDuplicateItem(itemType: ItemType, title: String): Boolean {
        return secureItemDao.findDuplicateItem(itemType, title) != null
    }
    
    /**
     * 智能检测重复项（根据类型使用不同的比较策略）
     * - DOCUMENT: 比较 documentNumber
     * - BANK_CARD: 比较 cardNumber  
     * - TOTP: 比较 issuer + accountName
     * - NOTE/PASSWORD: 比较 title
     * @return 找到的重复项，或 null
     */
    suspend fun findDuplicateSecureItem(
        itemType: ItemType,
        itemData: String,
        title: String,
        localOnly: Boolean = false
    ): takagi.ru.monica.data.SecureItem? {
        val existingItems = if (localOnly) {
            secureItemDao.getActiveLocalItemsByTypeSync(itemType)
        } else {
            secureItemDao.getActiveItemsByTypeSync(itemType)
        }
        
        return when (itemType) {
            ItemType.DOCUMENT -> {
                // 解析新项目的证件号码
                val newDocNumber = parseDocumentNumber(itemData)
                
                if (newDocNumber.isNullOrBlank()) {
                    // 无法解析证件号，退回到 title 匹配
                    existingItems.find { it.title == title }
                } else {
                    existingItems.find { existing ->
                        parseDocumentNumber(existing.itemData) == newDocNumber
                    }
                }
            }
            ItemType.BANK_CARD -> {
                // 解析新项目的卡号
                val newCardNumber = parseBankCardNumber(itemData)
                
                if (newCardNumber.isNullOrBlank()) {
                    existingItems.find { it.title == title }
                } else {
                    existingItems.find { existing ->
                        parseBankCardNumber(existing.itemData) == newCardNumber
                    }
                }
            }
            ItemType.TOTP -> {
                val normalizedTitle = normalizeText(title)
                val incoming = parseTotpFingerprint(itemData)

                if (incoming == null) {
                    existingItems.find { normalizeText(it.title) == normalizedTitle }
                } else {
                    existingItems.find { existing ->
                        val candidate = parseTotpFingerprint(existing.itemData)
                        if (candidate == null) {
                            normalizeText(existing.title) == normalizedTitle
                        } else {
                            val issuerAccountMatch =
                                incoming.issuer.isNotBlank() &&
                                    incoming.accountName.isNotBlank() &&
                                    candidate.issuer.isNotBlank() &&
                                    candidate.accountName.isNotBlank() &&
                                    incoming.issuer == candidate.issuer &&
                                    incoming.accountName == candidate.accountName

                            val secretMatch =
                                incoming.secret.isNotBlank() &&
                                    candidate.secret.isNotBlank() &&
                                    incoming.secret == candidate.secret

                            issuerAccountMatch || secretMatch
                        }
                    }
                }
            }
            else -> {
                // NOTE 和其他类型：只比较 title
                existingItems.find { it.title == title }
            }
        }
    }
    
    /**
     * 删除指定类型的所有项目
     */
    suspend fun deleteAllItemsByType(type: ItemType) {
        secureItemDao.deleteAllItemsByType(type)
    }
    
    /**
     * 删除所有TOTP认证器
     */
    suspend fun deleteAllTotpEntries() {
        secureItemDao.deleteAllItemsByType(ItemType.TOTP)
    }
    
    /**
     * 删除所有文档
     */
    suspend fun deleteAllDocuments() {
        secureItemDao.deleteAllItemsByType(ItemType.DOCUMENT)
    }
    
    /**
     * 删除所有银行卡
     */
    suspend fun deleteAllBankCards() {
        secureItemDao.deleteAllItemsByType(ItemType.BANK_CARD)
    }
    
    // =============== 回收站相关方法 ===============
    
    /**
     * 软删除项目（移动到回收站）
     */
    suspend fun softDeleteItem(item: SecureItem): SecureItem {
        val deletedItem = item.copy(
            isDeleted = true,
            deletedAt = java.util.Date(),
            updatedAt = java.util.Date()
        )
        return commitRoomThenMirror(
            roomCommit = {
                attachmentRepository?.softDelete(AttachmentOwner.secureItem(item.id))
                try {
                    secureItemDao.updateItem(deletedItem)
                    deletedItem
                } catch (error: Throwable) {
                    attachmentRepository?.restore(AttachmentOwner.secureItem(item.id))
                    throw error
                }
            },
            mirrorCommit = { mdbxRepository?.upsertSecureItem(it) },
            rollbackRoom = {
                secureItemDao.updateItem(item)
                attachmentRepository?.restore(AttachmentOwner.secureItem(item.id))
            },
            rollbackMirror = { mdbxRepository?.upsertSecureItem(item) }
        )
    }
    
    /**
     * 恢复已删除的项目
     */
    suspend fun restoreItem(item: SecureItem): SecureItem {
        val restoredItem = item.copy(
            isDeleted = false,
            deletedAt = null,
            updatedAt = java.util.Date()
        )
        return commitRoomThenMirror(
            roomCommit = {
                attachmentRepository?.restore(AttachmentOwner.secureItem(item.id))
                try {
                    secureItemDao.updateItem(restoredItem)
                    restoredItem
                } catch (error: Throwable) {
                    attachmentRepository?.softDelete(AttachmentOwner.secureItem(item.id))
                    throw error
                }
            },
            mirrorCommit = { mdbxRepository?.upsertSecureItem(it) },
            rollbackRoom = {
                secureItemDao.updateItem(item)
                attachmentRepository?.softDelete(AttachmentOwner.secureItem(item.id))
            },
            rollbackMirror = { mdbxRepository?.upsertSecureItem(item) }
        )
    }
    
    /**
     * 获取已删除的项目
     */
    fun getDeletedItems(): kotlinx.coroutines.flow.Flow<List<SecureItem>> {
        return secureItemDao.getDeletedItems()
    }
    
    /**
     * 获取未删除的项目（按类型）
     */
    fun getActiveItemsByType(type: ItemType): kotlinx.coroutines.flow.Flow<List<SecureItem>> {
        return secureItemDao.getActiveItemsByType(type)
    }

    /**
     * 获取本地安全项数量（排除 Bitwarden 和 KeePass 的数据）
     */
    suspend fun getLocalItemCountByType(type: ItemType): Int {
        return secureItemDao.getLocalItemCountByType(type)
    }

    /**
     * 获取本地已删除项目数量（排除 Bitwarden 和 KeePass 的数据）
     */
    suspend fun getLocalDeletedItemCount(): Int {
        return secureItemDao.getLocalDeletedItemCount()
    }

    suspend fun repairLegacyDetachedKeePassItems(
        databaseExists: suspend (Long) -> Boolean = { false }
    ): Int {
        if (legacyRepairCompleted) return 0
        return legacyRepairMutex.withLock {
            if (legacyRepairCompleted) return@withLock 0

            val items = secureItemDao.getAllItems().first()
            val staleIds = items
                .filter { isLegacyDetachedKeePassItem(it, databaseExists) }
                .map { it.id }
            if (staleIds.isNotEmpty()) {
                secureItemDao.clearKeePassBindingForIds(staleIds)
                Log.i(TAG, "Detached legacy KeePass-local secure item bindings: count=${staleIds.size}")
            }
            // Mark only after the scan and optional write succeed. An
            // exception/cancellation leaves the repair eligible for retry.
            legacyRepairCompleted = true
            staleIds.size
        }
    }

    private suspend fun isLegacyDetachedKeePassItem(
        item: SecureItem,
        databaseExists: suspend (Long) -> Boolean
    ): Boolean {
        val ownership = item.resolveOwnership()
        if (ownership !is SecureItemOwnership.KeePass) return false
        if (item.categoryId != null) return true

        val keepassDatabaseStillExists = databaseExists(ownership.databaseId)
        if (keepassDatabaseStillExists) return false

        return item.keepassEntryUuid.isNullOrBlank() && item.keepassGroupUuid.isNullOrBlank()
    }

    private fun parseTotpFingerprint(itemData: String): TotpFingerprint? {
        val resolvedItemData = decryptSensitiveValue
            ?.let { decrypt -> runCatching { decrypt(itemData) }.getOrDefault(itemData) }
            ?: itemData

        TotpDataResolver.parseStoredItemData(resolvedItemData)?.let { data ->
            return TotpFingerprint(
                issuer = normalizeText(data.issuer),
                accountName = normalizeText(data.accountName),
                secret = normalizeSecret(data.secret)
            )
        }

        runCatching {
            json.parseToJsonElement(resolvedItemData).jsonObject
        }.getOrNull()?.let { obj ->
            val issuer = obj["issuer"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val account = obj["accountName"]?.jsonPrimitive?.contentOrNull
                ?: obj["account"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val secret = obj["secret"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (issuer.isNotBlank() || account.isNotBlank() || secret.isNotBlank()) {
                return TotpFingerprint(
                    issuer = normalizeText(issuer),
                    accountName = normalizeText(account),
                    secret = normalizeSecret(secret)
                )
            }
        }

        parseOtpUriFingerprint(resolvedItemData)?.let { return it }

        return null
    }

    private fun parseBankCardNumber(itemData: String): String? {
        return CardWalletDataCodec.parseBankCardData(
            raw = itemData,
            decryptIfNeeded = decryptSensitiveValue
        )?.cardNumber
    }

    private fun parseDocumentNumber(itemData: String): String? {
        return CardWalletDataCodec.parseDocumentData(
            raw = itemData,
            decryptIfNeeded = decryptSensitiveValue
        )?.documentNumber
    }

    private fun parseOtpUriFingerprint(itemData: String): TotpFingerprint? {
        val raw = itemData.trim()
        val lower = raw.lowercase(Locale.ROOT)
        if (!lower.startsWith("otpauth://") && !lower.startsWith("motp://")) {
            return null
        }

        return runCatching {
            val uri = java.net.URI(raw)
            val queryParams = parseQueryParams(uri.rawQuery)
            val secret = queryParams["secret"].orEmpty()
            if (secret.isBlank()) return null

            val issuerFromQuery = queryParams["issuer"].orEmpty()
            val label = uri.rawPath?.removePrefix("/").orEmpty()
            val decodedLabel = urlDecode(label)
            val issuerFromLabel = decodedLabel.substringBefore(":", missingDelimiterValue = "").trim()
            val accountFromLabel = decodedLabel.substringAfter(":", missingDelimiterValue = decodedLabel).trim()
            val accountFromQuery = queryParams["accountName"].orEmpty()

            val issuer = issuerFromQuery.ifBlank { issuerFromLabel }
            val account = accountFromQuery.ifBlank {
                if (decodedLabel.contains(":")) accountFromLabel else decodedLabel
            }

            TotpFingerprint(
                issuer = normalizeText(issuer),
                accountName = normalizeText(account),
                secret = normalizeSecret(secret)
            )
        }.getOrNull()
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery
            .split("&")
            .mapNotNull { segment ->
                if (segment.isBlank()) return@mapNotNull null
                val key = segment.substringBefore("=")
                if (key.isBlank()) return@mapNotNull null
                val value = segment.substringAfter("=", "")
                urlDecode(key) to urlDecode(value)
            }
            .toMap()
    }

    private fun urlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrElse { value }
    }

    private fun normalizeText(value: String?): String {
        return value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun normalizeSecret(value: String?): String {
        return value
            ?.trim()
            ?.replace(" ", "")
            ?.replace("-", "")
            ?.uppercase(Locale.ROOT)
            .orEmpty()
    }
}

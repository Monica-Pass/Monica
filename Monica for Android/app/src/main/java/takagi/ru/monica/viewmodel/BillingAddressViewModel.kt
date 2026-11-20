package takagi.ru.monica.viewmodel


import android.content.Context
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.CardFaceAttachmentManager
import takagi.ru.monica.attachments.model.AttachmentOwner
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.asMonicaLocalCopy
import takagi.ru.monica.data.hasOwnershipConflict
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import java.util.Date

data class ParsedBillingAddressItem(
    val item: SecureItem,
    val addressData: BillingAddressData
)

class BillingAddressViewModel(
    private val repository: SecureItemRepository,
    private val securityManager: SecurityManager? = null,
    context: Context? = null
) : ViewModel() {

    private val attachmentFacade = context?.applicationContext?.let(AttachmentContainer::facade)
    private val cardFaceAttachmentManager = CardFaceAttachmentManager(repository, attachmentFacade, null)

    private val safeLogTitle = "账单地址"

    private val addressListSharingStarted = SharingStarted.WhileSubscribed(5000)
    private val allBillingAddressesSource: SharedFlow<List<SecureItem>> =
        repository.getItemsByType(ItemType.BILLING_ADDRESS)
            .shareIn(
                scope = viewModelScope,
                started = addressListSharingStarted,
                replay = 1,
            )

    val allBillingAddresses: StateFlow<List<SecureItem>> =
        allBillingAddressesSource
            .stateIn(
                scope = viewModelScope,
                started = addressListSharingStarted,
                initialValue = emptyList()
            )

    private val listDataCache = ItemDataSnapshotCache<BillingAddressData>()

    private val parsedBillingAddressesStateSource: Flow<LoadedListState<ParsedBillingAddressItem>> =
        allBillingAddressesSource
        .map { items ->
            val parsed = listDataCache.parse(items) { parseAddressData(it) }
            LoadedListState(
                items = items.mapIndexed { index, item ->
                    ParsedBillingAddressItem(
                        item = item,
                        addressData = parsed[index] ?: BillingAddressData()
                    )
                },
                isReady = true,
            )
        }
        .flowOn(Dispatchers.Default)

    val parsedBillingAddressesState: StateFlow<LoadedListState<ParsedBillingAddressItem>> =
        parsedBillingAddressesStateSource.stateIn(
            scope = viewModelScope,
            started = addressListSharingStarted,
            initialValue = LoadedListState(),
        )

    val parsedBillingAddressesReady: StateFlow<Boolean> = parsedBillingAddressesState
        .map { state -> state.isReady }
        .stateIn(
            scope = viewModelScope,
            started = addressListSharingStarted,
            initialValue = false,
        )

    val isLoading: StateFlow<Boolean> = parsedBillingAddressesReady
        .map { ready -> !ready }
        .stateIn(
            scope = viewModelScope,
            started = addressListSharingStarted,
            initialValue = true,
        )

    val parsedBillingAddresses: StateFlow<List<ParsedBillingAddressItem>> =
        parsedBillingAddressesState
            .map { state -> state.items }
            .stateIn(
                scope = viewModelScope,
                started = addressListSharingStarted,
                initialValue = emptyList(),
            )

    suspend fun getAddressById(id: Long): SecureItem? = repository.getItemById(id)

    fun addAddress(
        title: String,
        addressData: BillingAddressData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        categoryId: Long? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
        replicaGroupId: String? = null,
        cardFaceImageBytes: ByteArray? = null,
        onCreated: suspend (Long) -> Unit = {}
    ): Deferred<Long> {
        val ownedImageBytes = cardFaceImageBytes?.copyOf()
        return viewModelScope.async {
            try {
                val item = SecureItem(
                    id = 0,
                    itemType = ItemType.BILLING_ADDRESS,
                    title = title,
                    itemData = encodeAddressDataForLocalStorage(addressData),
                    notes = notes,
                    isFavorite = isFavorite,
                    categoryId = categoryId,
                    mdbxDatabaseId = mdbxDatabaseId,
                    mdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null,
                    replicaGroupId = replicaGroupId,
                    imagePaths = imagePaths,
                    createdAt = Date(),
                    updatedAt = Date()
                )
                val newId = repository.insertItem(item)
                onCreated(newId)
                if (addressData.cardFace != null) {
                    cardFaceAttachmentManager.update(newId, addressData.cardFace, ownedImageBytes, null).getOrThrow()
                }

                newId
            } finally {
                ownedImageBytes?.fill(0)
            }
        }
    }

    fun updateAddress(
        id: Long,
        title: String,
        addressData: BillingAddressData,
        notes: String = "",
        isFavorite: Boolean = false,
        imagePaths: String = "",
        categoryId: Long? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
        replicaGroupId: String? = null,
        cardFaceImageBytes: ByteArray? = null
    ): Deferred<Long> {
        var ownedImageBytes = cardFaceImageBytes?.copyOf()
        return viewModelScope.async {
            try {
                val existingItem = repository.getItemById(id) ?: error("Billing address no longer exists")
                val oldData = parseAddressData(existingItem.itemData)
                val backendChanged = existingItem.mdbxDatabaseId != mdbxDatabaseId
                if (ownedImageBytes == null && addressData.cardFace != null && backendChanged) {
                    ownedImageBytes = cardFaceAttachmentManager.readImage(id, addressData.cardFace.imageAttachmentName)
                }
                val updatedItem = existingItem.copy(
                    title = title,
                    itemData = encodeAddressDataForLocalStorage(addressData),
                    notes = notes,
                    isFavorite = isFavorite,
                    categoryId = categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    syncStatus = "NONE",
                    mdbxDatabaseId = mdbxDatabaseId,
                    mdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null,
                    replicaGroupId = replicaGroupId ?: existingItem.replicaGroupId,
                    updatedAt = Date(),
                    imagePaths = imagePaths
                )
                repository.updateItem(updatedItem)
                try {
                    if (addressData.cardFace != null || oldData?.cardFace != null) {
                        cardFaceAttachmentManager.update(
                            id, addressData.cardFace, ownedImageBytes, oldData?.cardFace?.imageAttachmentName,
                        ownerBackendChanged = backendChanged
                        ).getOrThrow()
                    }
                } catch (error: Exception) {
                    repository.updateItem(existingItem)
                    throw error
                }


                id
            } finally {
                ownedImageBytes?.fill(0)
            }
        }
    }

    fun deleteAddress(id: Long, softDelete: Boolean = true) {
        viewModelScope.launch {
            val item = repository.getItemById(id) ?: return@launch
            if (softDelete) {
                repository.softDeleteItem(item)

            } else {
                repository.deleteItem(item)

            }
        }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            val item = repository.getItemById(id) ?: return@launch
            repository.updateItem(
                item.copy(
                    isFavorite = !item.isFavorite,
                    updatedAt = Date()
                )
            )
        }
    }

    fun updateSortOrders(items: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateSortOrders(items)
        }
    }

    suspend fun copyAddressToMonicaLocal(
        item: SecureItem,
        categoryId: Long?
    ): Long? {
        if (item.itemType != ItemType.BILLING_ADDRESS || item.hasOwnershipConflict()) return null
        val localCopy = item.asMonicaLocalCopy(categoryId).copy(
            createdAt = Date(),
            updatedAt = Date()
        )
        return cloneAddressAttachments(item.id, repository.insertItem(localCopy))
    }

    suspend fun copyAddressToStorage(
        item: SecureItem,
        categoryId: Long?,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ): Long? {
        if (item.itemType != ItemType.BILLING_ADDRESS) return null
        val addressData = parseAddressData(item.itemData) ?: return null
        val copy = SecureItem(
            id = 0,
            itemType = ItemType.BILLING_ADDRESS,
            title = item.title,
            notes = item.notes,
            isFavorite = item.isFavorite,
            itemData = encodeAddressDataForLocalStorage(addressData),
            imagePaths = item.imagePaths,
            categoryId = if (mdbxDatabaseId == null) categoryId else null,
            mdbxDatabaseId = mdbxDatabaseId,
            mdbxFolderId = if (mdbxDatabaseId != null) mdbxFolderId else null,
            createdAt = Date(),
            updatedAt = Date()
        )
        return cloneAddressAttachments(item.id, repository.insertItem(copy))
    }

    private suspend fun cloneAddressAttachments(sourceId: Long, targetId: Long): Long? {
        return try {
            val source = requireNotNull(repository.getItemById(sourceId))
            if (parseAddressData(source.itemData)?.cardFace != null) requireNotNull(attachmentFacade)
            attachmentFacade?.cloneAttachmentsToNewOwner(
                sourceOwner = AttachmentOwner.secureItem(sourceId),
                targetOwner = AttachmentOwner.secureItem(targetId)
            )
            targetId
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            repository.deleteItemById(targetId)
            null
        }
    }

    suspend fun moveAddressToStorage(
        id: Long,
        categoryId: Long?,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ): Boolean {
        val item = repository.getItemById(id) ?: return false
        if (item.itemType != ItemType.BILLING_ADDRESS) return false
        val data = parseAddressData(item.itemData) ?: return false
        return try {
            updateAddress(
                id = id, title = item.title, addressData = data,
                notes = item.notes, isFavorite = item.isFavorite, imagePaths = item.imagePaths,
                categoryId = if (mdbxDatabaseId == null) categoryId else null,
                mdbxDatabaseId = mdbxDatabaseId, mdbxFolderId = mdbxFolderId,
                replicaGroupId = item.replicaGroupId
            ).await()
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    fun parseAddressData(jsonData: String): BillingAddressData? {
        return CardWalletDataCodec.parseBillingAddressData(
            raw = jsonData,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        )
    }

    private fun encodeAddressDataForLocalStorage(addressData: BillingAddressData): String {
        return encodeStoredSensitiveValueForNewWrite(
            CardWalletDataCodec.encodeBillingAddressData(addressData)
        )
    }

    private fun decryptStoredSensitiveValue(value: String): String {
        return securityManager
            ?.let { manager -> runCatching { manager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value) }
            ?: value
    }

    private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String {
        if (plainValue.isBlank()) return plainValue
        return securityManager?.encryptDataLegacyCompat(plainValue) ?: plainValue
    }

}

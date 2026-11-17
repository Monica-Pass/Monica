package takagi.ru.monica.autofill_ng

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*

/** Read-only adapters for the picker and IME. Virtual items must never be saved as SecureItems. */
internal class WalletAutofillRepository(
    private val database: PasswordDatabase,
    private val decrypt: (String) -> String,
) {
    private data class Origin(
        val ownerId: Long,
        val fieldId: Long?,
        val snapshotId: String?,
        val kind: EmbeddedWalletContent.Kind,
        val item: SecureItem,
    )

    @Volatile private var origins: Map<Long, Origin> = emptyMap()
    @Volatile private var nativeMdbxSource = false

    fun clear() { origins = emptyMap() }

    suspend fun loadEmbeddedItems(localOnly: Boolean = false): List<SecureItem> =
        loadEmbeddedItems(if (localOnly) AutofillScope.Local else AutofillScope.All, nativeMdbx = false)

    /** Scoped picker reads pair with AutofillPasswordSource's actual native MDBX wallets. */
    suspend fun loadEmbeddedItems(scope: AutofillScope): List<SecureItem> = loadEmbeddedItems(scope, nativeMdbx = true)

    private suspend fun loadEmbeddedItems(scope: AutofillScope, nativeMdbx: Boolean): List<SecureItem> = withContext(Dispatchers.IO) {
        nativeMdbxSource = nativeMdbx
        val loaded = database.withTransaction {
            val owners = database.passwordEntryDao().getWalletPasswordRows().associateBy { it.id }
            val fields = database.customFieldDao().getWalletFields().groupBy { it.entryId }
            buildList {
                owners.values.forEach { owner ->
                    currentCoroutineContext().ensureActive()
                    if (!owner.hasSingleOwner()) return@forEach
                    if (nativeMdbx && owner.mdbxDatabaseId != null) return@forEach
                    // The decorated metadata has the same ownership as every resulting wallet item.
                    // Apply the existing predicate before decoding any out-of-scope secret.
                    if (!owner.decorate(SecureItem(
                            itemType = ItemType.BANK_CARD, title = "", itemData = ""
                        )).matchesWalletAutofillScope(scope)) return@forEach
                    val ownerFields = fields[owner.id].orEmpty()
                    ownerFields.forEach { field ->
                        safely { embedded(owner, field) }?.let(::add)
                    }
                    // Even unreadable/newer snapshots suppress stale legacy mirrors.
                    listOf(EmbeddedWalletContent.Kind.BANK_CARD, EmbeddedWalletContent.Kind.ADDRESS).forEach { kind ->
                        if (ownerFields.none { it.title == EmbeddedWalletContent.fieldName(kind) }) {
                            safely { legacy(owner, kind) }?.let(::add)
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val visible = loaded.filter { it.item.matchesWalletAutofillScope(scope) }
        origins = visible.associateBy { it.item.id }
        visible.map { it.item }
    }

    /** Resolve at selection time: deletion, moving vaults and editing must not fill a stale secret. */
    suspend fun resolveCurrent(expected: SecureItem): SecureItem? = withContext(Dispatchers.IO) {
        if (nativeMdbxSource && expected.mdbxDatabaseId != null) return@withContext null
        val origin = if (expected.id < 0) origins[expected.id]?.takeIf { it.item == expected }
            ?: return@withContext null else null
        safely {
            database.withTransaction {
                val current = if (origin == null) {
                    database.secureItemDao().getItemById(expected.id)?.takeUnless { it.isDeleted }
                } else {
                    val owner = database.passwordEntryDao().getWalletPasswordRowById(origin.ownerId)
                        ?.takeIf { it.hasSingleOwner() } ?: return@withTransaction null
                    if (origin.fieldId != null) {
                        val field = database.customFieldDao().getFieldById(origin.fieldId)
                            ?.takeIf { it.entryId == origin.ownerId } ?: return@withTransaction null
                        embedded(owner, field)?.takeIf {
                            it.kind == origin.kind && it.snapshotId == origin.snapshotId
                        }?.item
                    } else {
                        if (database.customFieldDao().getWalletFieldsByEntryId(owner.id)
                                .any { it.title == EmbeddedWalletContent.fieldName(origin.kind) }) return@withTransaction null
                        legacy(owner, origin.kind)?.item
                    }
                } ?: return@withTransaction null
                current.takeIf {
                    it.itemType == expected.itemType &&
                        it.keepassDatabaseId == expected.keepassDatabaseId &&
                        it.mdbxDatabaseId == expected.mdbxDatabaseId &&
                        it.bitwardenVaultId == expected.bitwardenVaultId &&
                        it.categoryId == expected.categoryId &&
                        it.keepassGroupPath == expected.keepassGroupPath &&
                        it.mdbxFolderId == expected.mdbxFolderId &&
                        it.bitwardenFolderId == expected.bitwardenFolderId &&
                        (it.mdbxDatabaseId == null || database.localMdbxDatabaseDao()
                            .getAvailableDatabasesSnapshot().any { db -> db.id == it.mdbxDatabaseId })
                }
            }
        }
    }

    private fun embedded(owner: WalletPasswordRow, field: CustomField): Origin? {
        if (field.id <= 0 || field.id >= Long.MAX_VALUE / 2 || field.entryId != owner.id) return null
        val snapshot = (EmbeddedWalletContent.read(decrypt(field.value)) as? EmbeddedWalletContent.ReadResult.Available)
            ?.snapshot ?: return null
        if (snapshot.kind == EmbeddedWalletContent.Kind.NOTE ||
            field.title != EmbeddedWalletContent.fieldName(snapshot.kind)) return null
        val item = owner.decorate(snapshot.displayItem().copy(id = -field.id))
        return Origin(owner.id, field.id, snapshot.id, snapshot.kind, item)
    }

    private fun legacy(owner: WalletPasswordRow, kind: EmbeddedWalletContent.Kind): Origin? {
        if (owner.id <= 0 || owner.id >= Long.MAX_VALUE / 4) return null
        val data = when (kind) {
            EmbeddedWalletContent.Kind.BANK_CARD -> {
                if (owner.creditCardNumber.isBlank() && owner.creditCardHolder.isBlank()) return null
                val expiry = owner.creditCardExpiry.split('/')
                Json.encodeToString(BankCardData(
                    cardNumber = decrypt(owner.creditCardNumber), cardholderName = decrypt(owner.creditCardHolder),
                    expiryMonth = expiry.getOrNull(0).orEmpty().trim(),
                    expiryYear = expiry.getOrNull(1).orEmpty().trim(), cvv = decrypt(owner.creditCardCVV)))
            }
            EmbeddedWalletContent.Kind.ADDRESS -> {
                if (listOf(owner.addressLine, owner.city, owner.state, owner.zipCode, owner.country).all { it.isBlank() }) return null
                Json.encodeToString(BillingAddressData(streetAddress = decrypt(owner.addressLine),
                    city = decrypt(owner.city), stateProvince = decrypt(owner.state), postalCode = decrypt(owner.zipCode),
                    country = decrypt(owner.country), email = decrypt(owner.email), phone = decrypt(owner.phone)))
            }
            else -> return null
        }
        // Disjoint from positive standalone ids and -customFieldId. Never persisted.
        val id = Long.MIN_VALUE + owner.id * 2 + if (kind == EmbeddedWalletContent.Kind.ADDRESS) 1 else 0
        return Origin(owner.id, null, null, kind,
            owner.decorate(SecureItem(id = id, itemType = kind.itemType, title = "", itemData = data)))
    }

    private fun WalletPasswordRow.hasSingleOwner() = listOfNotNull(keepassDatabaseId, mdbxDatabaseId, bitwardenVaultId).size <= 1

    private fun WalletPasswordRow.decorate(item: SecureItem): SecureItem = item.copy(
        title = listOf(title, item.title).filter { it.isNotBlank() }.distinct().joinToString(" · "),
        notes = "", imagePaths = "", isFavorite = isFavorite || item.isFavorite,
        categoryId = categoryId, keepassDatabaseId = keepassDatabaseId, keepassGroupPath = keepassGroupPath,
        mdbxDatabaseId = mdbxDatabaseId, mdbxFolderId = mdbxFolderId,
        bitwardenVaultId = bitwardenVaultId, bitwardenFolderId = bitwardenFolderId,
    )

    private inline fun <T> safely(block: () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // No input, secret, or raw parser exception in logs.
}

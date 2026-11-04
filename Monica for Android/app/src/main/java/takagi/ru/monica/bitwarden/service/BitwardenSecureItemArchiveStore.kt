package takagi.ru.monica.bitwarden.service

import android.content.Context
import androidx.room.withTransaction
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SessionManager

/** Archive intent uses the same local dirty row and complete source as an ordinary edit. */
internal class BitwardenSecureItemArchiveStore(context: Context) {
    private val database = PasswordDatabase.getDatabase(context)
    private val repository = BitwardenRepository.getInstance(context)
    private val sourceStore = BitwardenCipherSourceStore(context)

    fun observe(): Flow<List<SecureItem>> = combine(
        database.secureItemDao().getArchivedItems(),
        database.bitwardenVaultDao().getAllVaultsFlow(),
        SessionManager.isUnlocked,
    ) { items, vaults, unlocked ->
        if (!unlocked) emptyList() else items.filter { item ->
            (item.bitwardenVaultId == null && item.keepassDatabaseId == null && item.mdbxDatabaseId == null) ||
                (vaults.any { it.id == item.bitwardenVaultId && !it.isLocked } &&
                    item.bitwardenVaultId?.let(repository::isVaultUnlocked) == true)
        }
    }

    suspend fun setArchived(expected: SecureItem, archived: Boolean): SecureItem {
        val generation = SessionManager.lockGeneration.value
        require(expected.keepassDatabaseId == null && expected.mdbxDatabaseId == null && !expected.isDeleted)
        require(expected.itemType in setOf(ItemType.NOTE, ItemType.BANK_CARD, ItemType.DOCUMENT))
        if (expected.bitwardenVaultId == null) {
            // A ZIP import is deliberately detached from the original provider. Keep its archive
            // state accessible, but expose only unarchive for these already archived local rows.
            require(expected.bitwardenCipherId == null && expected.isArchived && !archived)
            fun localActive() = SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation
            check(localActive()) { "Vault is locked" }
            return database.withTransaction {
                check(database.secureItemDao().getItemById(expected.id) == expected) { "Item changed; reopen it before unarchiving" }
                check(localActive()) { "Vault session changed" }
                val result = expected.copy(isArchived = false, archivedAt = null,
                    bitwardenArchiveIntent = null, updatedAt = Date())
                database.secureItemDao().update(result)
                check(localActive()) { "Vault session changed" }
                result
            }
        }
        val vaultId = expected.bitwardenVaultId
        val cipherId = requireNotNull(expected.bitwardenCipherId)
        val vaultGeneration = repository.getVaultSessionGeneration(vaultId)
        require(expected.keepassDatabaseId == null && expected.mdbxDatabaseId == null && !expected.isDeleted)
        require(expected.itemType in setOf(ItemType.NOTE, ItemType.BANK_CARD, ItemType.DOCUMENT))
        fun active(): Boolean = SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation && repository.isVaultUnlocked(vaultId) && repository.getVaultSessionGeneration(vaultId) == vaultGeneration
        check(active()) { "Vault is locked" }
        val result = database.withTransaction {
            check(database.secureItemDao().getItemById(expected.id) == expected) { "Item changed; reopen it before archiving" }
            val vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
            check(!vault.isLocked) { "Vault is locked" }
            val source = requireNotNull(sourceStore.read(vault, cipherId, requireNotNull(expected.bitwardenRevisionDate), ::active)) {
                "Complete cipher source unavailable; sync this account first"
            }
            BitwardenCipherOwnership.requireWritable(source, cipherId)
            require(BitwardenCipherDocument.canProject(source))
            check(active()) { "Vault session changed" }
            val now = Date()
            val result = expected.copy(isArchived = archived, archivedAt = if (archived) now else null,
                bitwardenArchiveIntent = archived, bitwardenLocalModified = true, syncStatus = "PENDING", updatedAt = now)
            database.secureItemDao().update(result)
            check(active()) { "Vault session changed" }
            result
        }
        repository.requestLocalMutationSync(vaultId)
        return result
    }
}

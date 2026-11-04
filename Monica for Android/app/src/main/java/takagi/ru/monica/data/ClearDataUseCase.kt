package takagi.ru.monica.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository

data class ClearDataSelection(
    val passwords: Boolean = false,
    val totp: Boolean = false,
    val notes: Boolean = false,
    val documents: Boolean = false,
    val bankCards: Boolean = false,
    val generatorHistory: Boolean = false,
) {
    val itemTypes: List<ItemType> get() = buildList {
        if (totp) add(ItemType.TOTP)
        if (notes) add(ItemType.NOTE)
        if (documents) add(ItemType.DOCUMENT)
        if (bankCards) add(ItemType.BANK_CARD)
    }
    val isEmpty: Boolean get() = !passwords && itemTypes.isEmpty() && !generatorHistory
}

enum class ClearDataPhase { PREPARING, PASSWORDS, TOTP, NOTES, DOCUMENTS, BANK_CARDS, GENERATOR_HISTORY }
enum class ClearDataStatus { RUNNING, COMPLETED, FAILED }

data class ClearDataProgress(
    val phase: ClearDataPhase = ClearDataPhase.PREPARING,
    val clearedEntries: Int = 0,
    val totalEntries: Int? = null,
    val status: ClearDataStatus = ClearDataStatus.RUNNING,
) {
    val isRunning: Boolean get() = status == ClearDataStatus.RUNNING
}

/**
 * Settings clear uses the same active-entry selection and mirror-first deletion
 * as before. Bounded batches avoid one transaction / vault save per record and
 * stay below SQLite's bind limit. Progress acknowledges committed batches only.
 */
class ClearDataUseCase(
    private val passwords: PasswordRepository,
    private val items: SecureItemRepository,
    private val clearGeneratorHistory: suspend () -> Unit,
) {
    suspend fun execute(selection: ClearDataSelection, onProgress: (ClearDataProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            require(!selection.isEmpty)
            var progress = ClearDataProgress()
            onProgress(progress)
            val passwordSnapshot = if (selection.passwords) passwords.getAllPasswordEntries().first() else emptyList()
            val types = selection.itemTypes
            val itemSnapshot = if (types.isNotEmpty()) items.getAllItemsIncludingArchived().first().filter { it.itemType in types } else emptyList()
            progress = progress.copy(totalEntries = passwordSnapshot.size + itemSnapshot.size)
            onProgress(progress)

            suspend fun <T> clearBatches(
                entries: List<T>, phase: ClearDataPhase, databaseId: (T) -> Long?, delete: suspend (List<T>) -> Unit,
            ) {
                if (entries.isEmpty()) return
                progress = progress.copy(phase = phase)
                onProgress(progress)
                // One MDBX database per commit keeps failure accounting and rollback local.
                for (group in entries.groupBy(databaseId).values) {
                    for (batch in group.chunked(500)) {
                        delete(batch)
                        progress = progress.copy(clearedEntries = progress.clearedEntries + batch.size)
                        onProgress(progress)
                    }
                }
            }
            clearBatches(passwordSnapshot, ClearDataPhase.PASSWORDS, PasswordEntry::mdbxDatabaseId, passwords::deletePasswordEntries)
            for (type in types) {
                val phase = when (type) {
                    ItemType.TOTP -> ClearDataPhase.TOTP
                    ItemType.NOTE -> ClearDataPhase.NOTES
                    ItemType.DOCUMENT -> ClearDataPhase.DOCUMENTS
                    ItemType.BANK_CARD -> ClearDataPhase.BANK_CARDS
                    else -> error("Unsupported clear type")
                }
                clearBatches(itemSnapshot.filter { it.itemType == type }, phase, SecureItem::mdbxDatabaseId, items::deleteItems)
            }
            if (selection.generatorHistory) {
                progress = progress.copy(phase = ClearDataPhase.GENERATOR_HISTORY)
                onProgress(progress)
                clearGeneratorHistory()
            }
            onProgress(progress.copy(status = ClearDataStatus.COMPLETED))
        }
}

package takagi.ru.monica.utils

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.DataExportImportManager.ExportItem
import java.util.Date

/** Ordinary backup retries may skip only a completely equal, standalone local item. */
internal object OrdinaryRestoreDuplicatePolicy {
    private val strictJson = Json { ignoreUnknownKeys = false }

    suspend fun findMatchingItem(
        database: PasswordDatabase,
        securityManager: SecurityManager,
        incoming: ExportItem,
        hasIncomingRelations: Boolean,
    ): SecureItem? {
        if (hasIncomingRelations || !hasStandaloneMetadata(incoming)) return null
        val itemType = ItemType.valueOf(incoming.itemType)
        val plain = readablePayload(securityManager, incoming.itemData) ?: return null
        if (!hasKnownStandalonePayload(itemType, plain)) return null
        // These reads include every attachment and password state, including deleted/archived
        // inbound relationships. No credential values need to be read to prove isolation.
        return database.withTransaction {
            database.secureItemDao().getActiveLocalItemsByTypeSync(itemType).firstOrNull { candidate ->
                val candidatePlain = readablePayload(securityManager, candidate.itemData)
                candidatePlain != null &&
                    isEquivalentStandalone(incoming, candidate, plain, candidatePlain) &&
                    database.attachmentDao().getAllBySecureItem(candidate.id).isEmpty() &&
                    !database.openHelper.readableDatabase.query(
                        "SELECT 1 FROM password_entries WHERE boundNoteId = ? LIMIT 1",
                        arrayOf<Any>(candidate.id),
                    ).use { it.moveToFirst() }
            }
        }
    }

    internal fun isEquivalentStandalone(
        incoming: ExportItem,
        candidate: SecureItem,
        incomingPlain: String,
        candidatePlain: String,
    ): Boolean {
        if (!hasStandaloneMetadata(incoming) || incomingPlain != candidatePlain) return false
        val itemType = ItemType.valueOf(incoming.itemType)
        if (!hasKnownStandalonePayload(itemType, incomingPlain)) return false
        val expected = SecureItem(
            itemType = itemType,
            title = incoming.title,
            notes = incoming.notes,
            isFavorite = incoming.isFavorite,
            sortOrder = incoming.sortOrder,
            createdAt = Date(incoming.createdAt),
            updatedAt = Date(incoming.updatedAt),
            itemData = incomingPlain,
            imagePaths = incoming.imagePaths,
            categoryId = incoming.categoryId,
            isDeleted = incoming.isDeleted,
            deletedAt = incoming.deletedAt?.let(::Date),
            isArchived = incoming.isArchived,
            archivedAt = incoming.archivedAt?.let(::Date),
        )
        // Data-class equality deliberately includes every stored ownership, sync, identity,
        // display and lifecycle field. Only the assigned row id and encryption envelope differ.
        return candidate.copy(id = 0, itemData = candidatePlain) == expected
    }

    private fun hasStandaloneMetadata(incoming: ExportItem): Boolean =
        incoming.itemType in setOf(ItemType.BANK_CARD.name, ItemType.DOCUMENT.name, ItemType.NOTE.name) &&
            !incoming.isFromAppExport && !incoming.isDeleted && !incoming.isArchived &&
            incoming.deletedAt == null && incoming.archivedAt == null &&
            incoming.categoryId == null && incoming.imagePaths.isEmpty() &&
            incoming.keepassDatabaseId == null && incoming.keepassGroupPath == null &&
            incoming.bitwardenVaultId == null && incoming.bitwardenFolderId == null &&
            incoming.importedCustomFields.isEmpty() && incoming.importedAuthenticatorKey.isNullOrEmpty()

    private fun hasKnownStandalonePayload(itemType: ItemType, plain: String): Boolean = try {
        when (itemType) {
            ItemType.BANK_CARD -> strictJson.decodeFromString<BankCardData>(plain).cardFace == null
            ItemType.DOCUMENT -> strictJson.decodeFromString<DocumentData>(plain).cardFace == null
            ItemType.NOTE -> { strictJson.decodeFromString<NoteData>(plain); true }
            else -> false
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // Unknown fields, references or unreadable payloads must remain independent rows.
        false
    }

    private fun readablePayload(securityManager: SecurityManager, value: String): String? = try {
        securityManager.decryptDataIfMonicaCiphertext(value)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }
}

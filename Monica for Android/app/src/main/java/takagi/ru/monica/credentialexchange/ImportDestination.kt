package takagi.ru.monica.credentialexchange

import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import java.util.UUID

enum class ImportDestinationKind { LOCAL, KEEPASS, MDBX, BITWARDEN }

data class ImportDestination(val kind: ImportDestinationKind, val databaseId: Long = 0) {
    init { require(if (kind == ImportDestinationKind.LOCAL) databaseId == 0L else databaseId > 0) }
    val key: String get() = "${kind.name}:$databaseId"
    val keepassId get() = databaseId.takeIf { kind == ImportDestinationKind.KEEPASS }
    val mdbxId get() = databaseId.takeIf { kind == ImportDestinationKind.MDBX }
    val bitwardenId get() = databaseId.takeIf { kind == ImportDestinationKind.BITWARDEN }

    fun contains(entry: PasswordEntry) = matches(entry.keepassDatabaseId, entry.mdbxDatabaseId, entry.bitwardenVaultId)
    fun contains(entry: SecureItem) = matches(entry.keepassDatabaseId, entry.mdbxDatabaseId, entry.bitwardenVaultId)
    fun contains(entry: PasskeyEntry) = matches(entry.keepassDatabaseId, entry.mdbxDatabaseId, entry.bitwardenVaultId)

    private fun matches(keepass: Long?, mdbx: Long?, bitwarden: Long?) =
        keepass == keepassId && mdbx == mdbxId && bitwarden == bitwardenId

    /** Source record and provider identifiers must never become the new record's identity. */
    fun password(entry: PasswordEntry) = entry.copy(
        id = 0, keepassDatabaseId = keepassId, keepassGroupPath = null,
        keepassEntryUuid = keepassId?.let { UUID.randomUUID().toString() }, keepassGroupUuid = null,
        mdbxDatabaseId = mdbxId, mdbxFolderId = null,
        bitwardenVaultId = bitwardenId, bitwardenCipherId = null, bitwardenFolderId = null,
        bitwardenRevisionDate = null, bitwardenLocalModified = bitwardenId != null,
        categoryId = null,
        replicaGroupId = mdbxId?.let { "password:${UUID.randomUUID()}" },
        boundNoteId = null, passkeyBindings = "", ssoRefEntryId = null,
    )

    fun secureItem(entry: SecureItem) = entry.copy(
        id = 0, keepassDatabaseId = keepassId, keepassGroupPath = null,
        keepassEntryUuid = keepassId?.let { UUID.randomUUID().toString() }, keepassGroupUuid = null,
        mdbxDatabaseId = mdbxId, mdbxFolderId = null,
        bitwardenVaultId = bitwardenId, bitwardenCipherId = null, bitwardenFolderId = null,
        bitwardenRevisionDate = null, bitwardenLocalModified = bitwardenId != null,
        bitwardenArchiveIntent = null,
        syncStatus = if (bitwardenId != null) "PENDING" else "NONE",
        categoryId = null,
        replicaGroupId = mdbxId?.let {
            takagi.ru.monica.repository.mdbxSecureItemObjectId(entry.copy(id = 0, replicaGroupId = null))
                .substringBefore(':') + ":${UUID.randomUUID()}"
        },
    )

    fun passkey(entry: PasskeyEntry) = entry.copy(
        id = 0, keepassDatabaseId = keepassId, keepassGroupPath = null,
        mdbxDatabaseId = mdbxId, mdbxFolderId = null,
        bitwardenVaultId = bitwardenId, bitwardenCipherId = null, bitwardenFolderId = null,
        syncStatus = if (bitwardenId != null) "PENDING" else "NONE",
        passkeyMode = if (keepassId != null) PasskeyEntry.MODE_KEEPASS_COMPAT else PasskeyEntry.MODE_BW_COMPAT,
        categoryId = null,
    )

    companion object {
        val Local = ImportDestination(ImportDestinationKind.LOCAL)
        fun fromKey(key: String): ImportDestination = runCatching {
            ImportDestination(ImportDestinationKind.valueOf(key.substringBefore(':')), key.substringAfter(':').toLong())
        }.getOrDefault(Local)
    }
}

data class ImportDestinationOption(
    val destination: ImportDestination,
    val title: String,
    val subtitle: String,
    val available: Boolean = true,
)

package takagi.ru.monica.data

import androidx.room.ColumnInfo

/** Only identity and ownership fields: counting never loads note bodies or attachments. */
data class SecureItemBackupMetadata(
    val id: Long,
    val itemType: ItemType,
    @ColumnInfo(name = "keepass_database_id") val keepassDatabaseId: Long?,
    @ColumnInfo(name = "keepass_entry_uuid") val keepassEntryUuid: String?,
    @ColumnInfo(name = "keepass_group_uuid") val keepassGroupUuid: String?,
    @ColumnInfo(name = "keepass_group_path") val keepassGroupPath: String?,
    @ColumnInfo(name = "bitwarden_vault_id") val bitwardenVaultId: Long?,
    @ColumnInfo(name = "bitwarden_cipher_id") val bitwardenCipherId: String?,
    @ColumnInfo(name = "bitwarden_revision_date") val bitwardenRevisionDate: String?,
    @ColumnInfo(name = "bitwarden_folder_id") val bitwardenFolderId: String?,
    @ColumnInfo(name = "bitwarden_local_modified") val bitwardenLocalModified: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "mdbx_database_id") val mdbxDatabaseId: Long?
) {
    fun isLocalOnlyItem(): Boolean = resolveSecureItemOwnership(
        keepassDatabaseId, keepassEntryUuid, keepassGroupUuid, keepassGroupPath,
        bitwardenVaultId, bitwardenCipherId, bitwardenRevisionDate, bitwardenFolderId,
        bitwardenLocalModified, syncStatus, mdbxDatabaseId
    ) is SecureItemOwnership.MonicaLocal
}

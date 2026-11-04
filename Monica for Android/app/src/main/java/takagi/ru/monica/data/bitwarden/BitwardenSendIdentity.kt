package takagi.ru.monica.data.bitwarden

import androidx.room.ColumnInfo

/** Non-content projection for counts and synchronization bookkeeping. */
data class BitwardenSendIdentity(
    val id: Long,
    @ColumnInfo(name = "vault_id") val vaultId: Long,
    @ColumnInfo(name = "bitwarden_send_id") val bitwardenSendId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
    @ColumnInfo(name = "is_dirty") val isDirty: Boolean,
)

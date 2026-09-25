package takagi.ru.monica.data

import androidx.room.ColumnInfo

/** Keyboard projection: omit notes, histories, attachments and other editor-only payloads. */
data class ImePasswordRow(
    val id: Long,
    val title: String,
    val username: String,
    val website: String,
    val password: String,
    val appName: String,
    val appPackageName: String,
    val isFavorite: Boolean,
    val authenticatorKey: String,
    val keepassDatabaseId: Long?,
    @ColumnInfo(name = "mdbx_database_id") val mdbxDatabaseId: Long?,
    @ColumnInfo(name = "bitwarden_vault_id") val bitwardenVaultId: Long?,
)

package takagi.ru.monica.data.bitwarden

import androidx.room.*
import takagi.ru.monica.data.PasswordEntry

/** Raw wire history and row mapping are MDK encrypted; parent entry deletion cascades. */
@Entity(tableName = "bitwarden_password_history_states", foreignKeys = [ForeignKey(
    entity = PasswordEntry::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["vault_id", "cipher_id"])])
data class BitwardenPasswordHistoryState(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "vault_id") val vaultId: Long,
    @ColumnInfo(name = "cipher_id") val cipherId: String,
    @ColumnInfo(name = "server_revision") val serverRevision: String,
    @ColumnInfo(name = "payload_mdk") val payloadMdk: String,
    @ColumnInfo(name = "local_version") val localVersion: Long = 0,
    @ColumnInfo(name = "ack_version") val ackVersion: Long = 0
)

@Dao
interface BitwardenPasswordHistoryStateDao {
    @Query("SELECT * FROM bitwarden_password_history_states WHERE entry_id = :entryId")
    suspend fun get(entryId: Long): BitwardenPasswordHistoryState?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: BitwardenPasswordHistoryState)
}

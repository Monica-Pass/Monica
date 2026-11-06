package takagi.ru.monica.data.dedup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Payload (including titles) is MDK-encrypted. Independent of cascading parent deletion. */
@Entity(tableName = "single_vault_dedup_recovery")
data class SingleVaultDedupRecoveryEntity(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val createdAt: Long,
    val projectCount: Int,
    val sealedPayload: String,
    val state: String,
) {
    override fun toString(): String = "SingleVaultDedupRecoveryEntity(<redacted>)"
}

@Dao
interface SingleVaultDedupRecoveryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(batch: SingleVaultDedupRecoveryEntity)

    @Query("SELECT * FROM single_vault_dedup_recovery WHERE id = :id")
    suspend fun get(id: String): SingleVaultDedupRecoveryEntity?

    @Query("SELECT * FROM single_vault_dedup_recovery WHERE state != 'RESTORED' ORDER BY createdAt DESC")
    suspend fun pending(): List<SingleVaultDedupRecoveryEntity>

    @Query("UPDATE single_vault_dedup_recovery SET state = :state WHERE id = :id AND state = :expected")
    suspend fun transition(id: String, expected: String, state: String): Int
}

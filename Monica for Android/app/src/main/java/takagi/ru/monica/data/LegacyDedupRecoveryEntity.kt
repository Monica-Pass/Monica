package takagi.ru.monica.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Inert schema compatibility for installs that used the removed dedup engine.
 * Keep the table and encrypted payload untouched; no runtime DAO or engine is exposed.
 */
@Entity(tableName = "single_vault_dedup_recovery")
data class LegacyDedupRecoveryEntity(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val createdAt: Long,
    val projectCount: Int,
    val sealedPayload: String,
    val state: String,
) {
    override fun toString(): String = "LegacyDedupRecoveryEntity(<redacted>)"
}

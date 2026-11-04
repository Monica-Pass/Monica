package takagi.ru.monica.data.bitwarden

import androidx.room.*

/** Complete encrypted source, separate from the editable projections and optional diagnostics. */
@Entity(
    tableName = "bitwarden_cipher_sources",
    primaryKeys = ["vaultId", "cipherId", "revisionDate"],
    foreignKeys = [ForeignKey(entity = BitwardenVault::class, parentColumns = ["id"], childColumns = ["vaultId"], onDelete = ForeignKey.CASCADE)]
)
data class BitwardenCipherSource(
    val vaultId: Long,
    val cipherId: String,
    val revisionDate: String,
    val accountKey: String,
    val sealedDocument: String
)

@Dao
interface BitwardenCipherSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(source: BitwardenCipherSource)

    @Query("SELECT * FROM bitwarden_cipher_sources WHERE vaultId = :vaultId AND cipherId = :cipherId AND revisionDate = :revision")
    suspend fun get(vaultId: Long, cipherId: String, revision: String): BitwardenCipherSource?

    /** Keep the latest observation and the revision still bound to pending local edits. */
    @Query("DELETE FROM bitwarden_cipher_sources WHERE vaultId = :vaultId AND cipherId = :cipherId AND revisionDate != :observed AND revisionDate != :bound")
    suspend fun retain(vaultId: Long, cipherId: String, observed: String, bound: String)
}

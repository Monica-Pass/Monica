package takagi.ru.monica.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordHistoryDao {
    @Query("SELECT * FROM password_entries WHERE id = :entryId")
    fun observeOwningEntry(entryId: Long): Flow<PasswordEntry?>

    @Query("SELECT * FROM password_history_entries WHERE id = :id")
    suspend fun getById(id: Long): PasswordHistoryEntry?

    @Query(
        """
        SELECT * FROM password_history_entries
        ORDER BY entry_id ASC, last_used_at DESC, id DESC
        """
    )
    suspend fun getAllHistorySync(): List<PasswordHistoryEntry>

    @Query(
        """
        SELECT * FROM password_history_entries
        WHERE entry_id = :entryId
        ORDER BY last_used_at DESC, id DESC
        """
    )
    fun getHistoryByEntryId(entryId: Long): Flow<List<PasswordHistoryEntry>>

    @Query(
        """
        SELECT * FROM password_history_entries
        WHERE entry_id = :entryId
        ORDER BY last_used_at DESC, id DESC
        """
    )
    suspend fun getHistoryByEntryIdSync(entryId: Long): List<PasswordHistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PasswordHistoryEntry): Long

    @Query("UPDATE password_history_entries SET password = :password WHERE id = :id")
    suspend fun updatePasswordById(id: Long, password: String)

    @Query("DELETE FROM password_history_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM password_history_entries
        WHERE entry_id = :entryId
          AND id NOT IN (
              SELECT id
              FROM password_history_entries
              WHERE entry_id = :entryId
              ORDER BY last_used_at DESC, id DESC
              LIMIT :limit
          )
        """
    )
    suspend fun trimToLimit(entryId: Long, limit: Int)

    @Query("DELETE FROM password_history_entries WHERE entry_id = :entryId")
    suspend fun deleteByEntryId(entryId: Long)
}

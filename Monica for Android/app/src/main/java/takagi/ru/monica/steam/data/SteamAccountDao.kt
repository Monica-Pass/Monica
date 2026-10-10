package takagi.ru.monica.steam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamAccountDao {
    @Query("SELECT * FROM steam_accounts ORDER BY sortOrder ASC, id ASC")
    fun observeAccounts(): Flow<List<SteamAccountEntity>>

    @Query("SELECT * FROM steam_accounts ORDER BY sortOrder ASC, id ASC")
    suspend fun getAccounts(): List<SteamAccountEntity>

    @Query("SELECT * FROM steam_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SteamAccountEntity?

    @Query("SELECT * FROM steam_accounts WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): SteamAccountEntity?

    @Query("SELECT COUNT(*) FROM steam_accounts")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM steam_accounts")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: SteamAccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCopy(account: SteamAccountEntity): Long

    @Query("UPDATE steam_accounts SET categoryId = :categoryId WHERE id = :id")
    suspend fun moveToCategory(id: Long, categoryId: Long?)

    @Update
    suspend fun update(account: SteamAccountEntity)

    @Query("DELETE FROM steam_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM steam_accounts")
    suspend fun deleteAll()

    @Query("UPDATE steam_accounts SET selected = 0")
    suspend fun clearSelected()

    @Query("UPDATE steam_accounts SET selected = 1 WHERE id = :id")
    suspend fun markSelected(id: Long)

    @Query("UPDATE steam_accounts SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun updateSortOrders(items: List<Pair<Long, Int>>) {
        items.forEach { (id, sortOrder) ->
            updateSortOrder(id, sortOrder)
        }
    }

    @Transaction
    suspend fun selectAccount(id: Long) {
        clearSelected()
        markSelected(id)
    }
}

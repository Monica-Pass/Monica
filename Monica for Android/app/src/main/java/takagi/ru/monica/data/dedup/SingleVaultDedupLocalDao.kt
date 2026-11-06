package takagi.ru.monica.data.dedup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import takagi.ru.monica.data.*
import takagi.ru.monica.attachments.model.Attachment

/** Unfiltered reads are deliberate: archived/deleted siblings and inbound links must not disappear. */
@Dao
interface SingleVaultDedupLocalDao {
    @Query("SELECT * FROM password_entries ORDER BY id") suspend fun passwords(): List<PasswordEntry>
    @Query("SELECT * FROM secure_items ORDER BY id") suspend fun items(): List<SecureItem>
    @Query("SELECT * FROM custom_fields ORDER BY entry_id, sort_order, id") suspend fun fields(): List<CustomField>
    @Query("SELECT * FROM attachments ORDER BY id") suspend fun attachments(): List<Attachment>
    @Query("SELECT * FROM password_history_entries ORDER BY id") suspend fun history(): List<PasswordHistoryEntry>
    @Query("SELECT * FROM passkeys ORDER BY id") suspend fun passkeys(): List<PasskeyEntry>
    @Query("SELECT * FROM timeline_version_snapshots ORDER BY id") suspend fun timeline(): List<TimelineVersionSnapshot>
    @Query("SELECT * FROM operation_logs ORDER BY id") suspend fun logs(): List<OperationLog>
    @Query("SELECT * FROM password_archive_sync_meta ORDER BY entry_id") suspend fun archive(): List<PasswordArchiveSyncMeta>
    @Query("SELECT * FROM password_page_aggregate_stack_entries ORDER BY item_key") suspend fun stacks(): List<PasswordPageAggregateStackEntry>
    @Update suspend fun updatePasswords(rows: List<PasswordEntry>): Int
    @Update suspend fun updateItems(rows: List<SecureItem>): Int
    @Update suspend fun updateAttachments(rows: List<Attachment>): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPasswords(rows: List<PasswordEntry>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertItems(rows: List<SecureItem>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFields(rows: List<CustomField>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertHistory(rows: List<PasswordHistoryEntry>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertArchive(rows: List<PasswordArchiveSyncMeta>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttachments(rows: List<Attachment>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTimeline(rows: List<TimelineVersionSnapshot>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLogs(rows: List<OperationLog>)
}

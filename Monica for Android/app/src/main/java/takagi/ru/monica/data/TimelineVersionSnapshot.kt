package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "timeline_version_snapshots",
    indices = [
        Index(value = ["operation_log_id"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["item_type", "item_id"])
    ]
)
data class TimelineVersionSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "operation_log_id")
    val operationLogId: Long,
    @ColumnInfo(name = "item_type")
    val itemType: String,
    @ColumnInfo(name = "item_id")
    val itemId: Long,
    @ColumnInfo(name = "operation_type")
    val operationType: String,
    @ColumnInfo(name = "encrypted_changes_json")
    val encryptedChangesJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

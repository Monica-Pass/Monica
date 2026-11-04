package takagi.ru.monica.data.bitwarden

import kotlinx.serialization.Serializable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Serializable
@Entity(
    tableName = "bitwarden_sends",
    indices = [
        Index(value = ["vault_id"]),
        Index(value = ["bitwarden_send_id"]),
        Index(value = ["vault_id", "bitwarden_send_id"], unique = true),
        Index(value = ["updated_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = BitwardenVault::class,
            parentColumns = ["id"],
            childColumns = ["vault_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION
        )
    ]
)
data class BitwardenSend(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "vault_id")
    val vaultId: Long,

    @ColumnInfo(name = "bitwarden_send_id")
    val bitwardenSendId: String,

    @ColumnInfo(name = "access_id")
    val accessId: String,

    @ColumnInfo(name = "key_base64")
    val keyBase64: String? = null,

    @ColumnInfo(name = "type")
    val type: Int = TYPE_TEXT,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "text_content")
    val textContent: String? = null,

    @ColumnInfo(name = "is_text_hidden")
    val isTextHidden: Boolean = false,

    @ColumnInfo(name = "file_name")
    val fileName: String? = null,

    @ColumnInfo(name = "file_size")
    val fileSize: String? = null,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,

    @ColumnInfo(name = "max_access_count")
    val maxAccessCount: Int? = null,

    @ColumnInfo(name = "has_password")
    val hasPassword: Boolean = false,

    @ColumnInfo(name = "disabled")
    val disabled: Boolean = false,

    @ColumnInfo(name = "hide_email")
    val hideEmail: Boolean = false,

    @ColumnInfo(name = "revision_date")
    val revisionDate: String = "",

    @ColumnInfo(name = "expiration_date")
    val expirationDate: String? = null,

    @ColumnInfo(name = "deletion_date")
    val deletionDate: String? = null,

    @ColumnInfo(name = "share_url")
    val shareUrl: String = "",

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * 本地新建/修改尚未与服务器对账时为 true。
     *
     * 在 Bitwarden Vaultwarden / 官方 sync API 写后读不一致的场景下，本地刚创建的 Send 可能
     * 在下一次 fullSync 的服务器返回中尚未出现。该标志用于告知 Sync 路径在 deleteNotIn 时
     * 跳过 dirty 行，避免本地新建的 Send 被立即清掉。
     *
     * 默认 false（已对账状态）。createTextSend / createFileSend 写入时置 true，syncSend
     * 在服务器侧确认收到后清零。
     */
    @ColumnInfo(name = "is_dirty", defaultValue = "0")
    val isDirty: Boolean = false,

    @ColumnInfo(name = "auth_type")
    val authType: Int? = null,

    @ColumnInfo(name = "recipient_emails")
    val recipientEmails: String? = null,

    @ColumnInfo(name = "encrypted_cache")
    val encryptedCache: String? = null,

    @ColumnInfo(name = "cache_version", defaultValue = "0")
    val cacheVersion: Int = 0
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_FILE = 1
    }

    val isTextType: Boolean
        get() = type == TYPE_TEXT

    val isFileType: Boolean
        get() = type == TYPE_FILE
}


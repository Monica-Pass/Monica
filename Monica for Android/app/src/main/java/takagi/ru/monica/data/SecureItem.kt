package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 统一的安全数据项实体
 * 支持多种类型：TOTP验证器、银行卡、证件、笔记
 */
@Entity(
    tableName = "secure_items",
    indices = [
        Index(value = ["isDeleted"]),
        Index(value = ["replica_group_id"], name = "index_secure_items_replica_group_id"),
        Index(value = ["keepass_entry_uuid"], name = "index_secure_items_keepass_entry_uuid"),
        Index(value = ["mdbx_database_id"], name = "index_secure_items_mdbx_database_id"),
        Index(value = ["mdbx_database_id", "mdbx_folder_id"], name = "index_secure_items_mdbx_database_folder"),
        Index(
            value = ["bitwarden_vault_id", "bitwarden_cipher_id"],
            unique = true,
            name = "index_secure_items_bitwarden_vault_cipher_unique"
        )
    ]
)
data class SecureItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 通用字段
    val itemType: ItemType,  // 数据类型:TOTP、BANK_CARD、DOCUMENT
    val title: String,       // 标题/名称
    val notes: String = "",  // 备注
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,  // 排序顺序(用于拖动排序)
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    
    // 类型特定数据(JSON格式存储)
    val itemData: String,    // 存储不同类型的具体数据(JSON)
    
    // 图片附件路径(加密存储)
    val imagePaths: String = "", // JSON数组,存储图片文件路径
    
    // 分类ID（用于验证器等支持分类的类型）
    @ColumnInfo(defaultValue = "NULL")
    val categoryId: Long? = null,

    // 归属的 KeePass 数据库（用于统一目标存储选择）
    @ColumnInfo(name = "keepass_database_id", defaultValue = "NULL")
    val keepassDatabaseId: Long? = null,
    @ColumnInfo(name = "keepass_group_path", defaultValue = "NULL")
    val keepassGroupPath: String? = null,
    @ColumnInfo(name = "keepass_entry_uuid", defaultValue = "NULL")
    val keepassEntryUuid: String? = null,
    @ColumnInfo(name = "keepass_group_uuid", defaultValue = "NULL")
    val keepassGroupUuid: String? = null,

    // MDBX project-centric 数据库归属
    @ColumnInfo(name = "mdbx_database_id", defaultValue = "NULL")
    val mdbxDatabaseId: Long? = null,
    @ColumnInfo(name = "mdbx_folder_id", defaultValue = "NULL")
    val mdbxFolderId: String? = null,

    // 回收站功能 - 软删除字段
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,     // 是否已删除（在回收站中）
    @ColumnInfo(defaultValue = "NULL")
    val deletedAt: Date? = null,         // 删除时间（用于自动清空）

    @ColumnInfo(defaultValue = "0")
    val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    val archivedAt: Date? = null,
    // Null preserves the source; only the archive action creates a mutation intent.
    @ColumnInfo(name = "bitwarden_archive_intent", defaultValue = "NULL")
    val bitwardenArchiveIntent: Boolean? = null,

    @ColumnInfo(name = "replica_group_id", defaultValue = "NULL")
    val replicaGroupId: String? = null,
    
    // Bitwarden 同步字段
    @ColumnInfo(name = "bitwarden_vault_id", defaultValue = "NULL")
    val bitwardenVaultId: Long? = null,           // 关联的 Bitwarden Vault
    
    @ColumnInfo(name = "bitwarden_cipher_id", defaultValue = "NULL")
    val bitwardenCipherId: String? = null,        // Bitwarden Cipher UUID
    
    @ColumnInfo(name = "bitwarden_folder_id", defaultValue = "NULL")
    val bitwardenFolderId: String? = null,        // Bitwarden Folder UUID
    
    @ColumnInfo(name = "bitwarden_revision_date", defaultValue = "NULL")
    val bitwardenRevisionDate: String? = null,    // 最后同步的服务器版本日期
    
    @ColumnInfo(name = "bitwarden_local_modified", defaultValue = "0")
    val bitwardenLocalModified: Boolean = false,  // 本地是否有未同步的修改
    
    @ColumnInfo(name = "sync_status", defaultValue = "NONE")
    val syncStatus: String = "NONE"               // 同步状态: NONE, PENDING, SYNCING, SYNCED, FAILED, CONFLICT
)

/**
 * 数据项类型枚举
 */
enum class ItemType {
    PASSWORD,    // 密码
    TOTP,        // 验证器
    BANK_CARD,   // 银行卡
    DOCUMENT,    // 证件
    BILLING_ADDRESS, // 账单地址
    PAYMENT_ACCOUNT, // 支付方式
    NOTE         // 笔记
}

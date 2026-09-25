package takagi.ru.monica.data

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * Password entry entity for Room database
 */
@Parcelize
@Entity(
    tableName = "password_entries",
    indices = [
        Index(value = ["isDeleted"]),
        Index(value = ["isArchived"]),
        Index(value = ["replica_group_id"], name = "index_password_entries_replica_group_id"),
        Index(value = ["keepass_entry_uuid"], name = "index_password_entries_keepass_entry_uuid"),
        Index(value = ["mdbx_database_id"], name = "index_password_entries_mdbx_database_id"),
        Index(value = ["mdbx_database_id", "mdbx_folder_id"], name = "index_password_entries_mdbx_database_folder"),
        Index(
            value = ["bitwarden_vault_id", "bitwarden_cipher_id"],
            unique = true,
            name = "index_password_entries_bitwarden_vault_cipher_unique"
        )
    ]
)
data class PasswordEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val website: String,
    val username: String,
    val password: String, // This will be encrypted
    val notes: String = "",
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0, // 排序顺序(用于拖动排序)
    val isGroupCover: Boolean = false, // 是否作为分组封面
    val appPackageName: String = "", // 关联的应用包名（用于自动填充匹配）
    val appName: String = "", // 关联的应用名称（用于显示）
    
    // Phase 7: 个人信息字段
    val email: String = "",
    val phone: String = "",
    
    // Phase 7: 地址信息字段
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    
    // Phase 7: 支付信息字段 (加密存储)
    val creditCardNumber: String = "",      // 加密存储
    val creditCardHolder: String = "",
    val creditCardExpiry: String = "",       // 格式: MM/YY
    val creditCardCVV: String = "",           // 加密存储
    
    val categoryId: Long? = null, // 分类ID
    @ColumnInfo(defaultValue = "NULL")
    val boundNoteId: Long? = null, // 绑定的笔记ID
    
    // 本地 KeePass 数据库归属
    @ColumnInfo(defaultValue = "NULL")
    val keepassDatabaseId: Long? = null, // 归属的 KeePass 数据库ID
    @ColumnInfo(defaultValue = "NULL")
    val keepassGroupPath: String? = null, // 归属的 KeePass 分组路径
    @ColumnInfo(name = "keepass_entry_uuid", defaultValue = "NULL")
    val keepassEntryUuid: String? = null, // KeePass 原生条目 UUID
    @ColumnInfo(name = "keepass_group_uuid", defaultValue = "NULL")
    val keepassGroupUuid: String? = null, // KeePass 当前分组 UUID

    // MDBX project-centric 数据库归属
    @ColumnInfo(name = "mdbx_database_id", defaultValue = "NULL")
    val mdbxDatabaseId: Long? = null, // 归属的 MDBX 数据库ID
    @ColumnInfo(name = "mdbx_folder_id", defaultValue = "NULL")
    val mdbxFolderId: String? = null, // 归属的 MDBX 文件夹ID
    
    // 关联的验证器密钥 (TOTP Secret)
    val authenticatorKey: String = "",  // 用于存储绑定的TOTP验证器密钥

    // 绑定的通行密钥元数据（JSON）
    @ColumnInfo(name = "passkey_bindings", defaultValue = "")
    val passkeyBindings: String = "",

    // SSH 密钥对元数据（JSON）
    @ColumnInfo(name = "ssh_key_data", defaultValue = "")
    val sshKeyData: String = "",
    
    // 第三方登录(SSO)字段
    @ColumnInfo(defaultValue = "PASSWORD")
    val loginType: String = "PASSWORD",  // 登录类型: PASSWORD / SSO / WIFI
    @ColumnInfo(defaultValue = "")
    val ssoProvider: String = "",        // SSO提供商: GOOGLE, APPLE, FACEBOOK 等
    @ColumnInfo(defaultValue = "NULL")
    val ssoRefEntryId: Long? = null,     // 引用的账号条目ID

    // WIFI 条目扩展数据（JSON 序列化的 takagi.ru.monica.data.model.WifiData）
    // 仅当 loginType == "WIFI" 时使用；其他登录类型保持空字符串以节省空间。
    @ColumnInfo(name = "wifi_metadata", defaultValue = "")
    val wifiMetadata: String = "",

    // 自定义图标字段
    @ColumnInfo(defaultValue = "NONE")
    val customIconType: String = "NONE", // NONE / SIMPLE_ICON / UPLOADED
    @ColumnInfo(defaultValue = "NULL")
    val customIconValue: String? = null, // SIMPLE_ICON: slug, UPLOADED: local file name
    @ColumnInfo(defaultValue = "0")
    val customIconUpdatedAt: Long = 0L,
    
    // 回收站功能 - 软删除字段
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,      // 是否已删除（在回收站中）
    @ColumnInfo(defaultValue = "NULL")
    val deletedAt: java.util.Date? = null, // 删除时间（用于自动清空）

    // 归档功能 - 临时隐藏字段（不删除）
    @ColumnInfo(defaultValue = "0")
    val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    val archivedAt: java.util.Date? = null,

    @ColumnInfo(name = "replica_group_id", defaultValue = "NULL")
    val replicaGroupId: String? = null,
    
    // === Bitwarden 集成字段 ===
    // 当此条目来自 Bitwarden 时，以下字段有值
    @ColumnInfo(name = "bitwarden_vault_id", defaultValue = "NULL")
    val bitwardenVaultId: Long? = null,   // 归属的 Bitwarden Vault ID
    
    @ColumnInfo(name = "bitwarden_cipher_id", defaultValue = "NULL")
    val bitwardenCipherId: String? = null, // Bitwarden Cipher UUID
    
    @ColumnInfo(name = "bitwarden_folder_id", defaultValue = "NULL")
    val bitwardenFolderId: String? = null, // Bitwarden Folder UUID
    
    @ColumnInfo(name = "bitwarden_revision_date", defaultValue = "NULL")
    val bitwardenRevisionDate: String? = null, // 服务器版本号 (ISO 8601)
    
    @ColumnInfo(name = "bitwarden_cipher_type", defaultValue = "1")
    val bitwardenCipherType: Int = 1,     // Cipher 类型: 1=Login, 2=SecureNote, 3=Card, 4=Identity
    
    @ColumnInfo(name = "bitwarden_local_modified", defaultValue = "0")
    val bitwardenLocalModified: Boolean = false // 本地是否有未同步的修改
) : Parcelable {
    
    /**
     * 是否使用第三方登录
     */
    fun isSsoLogin(): Boolean = loginType == "SSO"

    /**
     * 是否为 WIFI 类型条目
     */
    fun isWifiEntry(): Boolean = loginType.equals("WIFI", ignoreCase = true)

    fun isGpgKeyEntry(): Boolean = loginType.equals("GPG_KEY", ignoreCase = true)

    fun isApiKeyEntry(): Boolean = loginType.equals(takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE, ignoreCase = true)

    /** Stored keys must never be offered as website or app login passwords. */
    fun isKeyCredential(): Boolean = isGpgKeyEntry() || isApiKeyEntry()
    
    /**
     * 获取SSO提供商枚举
     */
    fun getSsoProviderEnum(): SsoProvider? {
        return if (isSsoLogin() && ssoProvider.isNotEmpty()) {
            SsoProvider.fromName(ssoProvider)
        } else null
    }
    
    /**
     * 是否来自 Bitwarden
     */
    fun isBitwardenEntry(): Boolean = resolveOwnership() is PasswordOwnership.Bitwarden

    /**
     * 是否有 Bitwarden 归属
     */
    fun hasBitwardenBinding(): Boolean = bitwardenVaultId != null

    /**
     * 是否已绑定到 Bitwarden 远端 cipher
     */
    fun hasBitwardenCipherBinding(): Boolean = bitwardenVaultId != null && !bitwardenCipherId.isNullOrBlank()
    
    /**
     * 是否有待同步的 Bitwarden 修改
     */
    fun hasPendingBitwardenSync(): Boolean = hasBitwardenBinding() && bitwardenLocalModified
    
    /**
     * 是否来自 KeePass
     */
    fun isKeePassEntry(): Boolean = resolveOwnership() is PasswordOwnership.KeePass

    /**
     * 是否来自 MDBX
     */
    fun isMdbxEntry(): Boolean = resolveOwnership() is PasswordOwnership.Mdbx

    /**
     * 是否存在多重 owner 冲突
     */
    fun hasOwnershipConflict(): Boolean = resolveOwnership() is PasswordOwnership.Conflict
    
    /**
     * 是否为本地条目 (不关联任何外部数据库)
     */
    fun isLocalOnlyEntry(): Boolean = resolveOwnership() is PasswordOwnership.MonicaLocal
}

package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Passkey（通行密钥）实体
 * 
 * 存储基于 FIDO2/WebAuthn 标准的 Passkey 凭据信息。
 * 私钥存储在 Android Keystore 中，此表仅存储元数据和公钥。
 * 
 * @property credentialId 凭据 ID（支持 Base64URL 或 UUID 文本格式）
 * @property rpId 依赖方 ID（通常是域名，如 google.com）
 * @property rpName 依赖方显示名称
 * @property userId 用户 ID（由依赖方提供，Base64 编码）
 * @property userName 用户名/邮箱
 * @property userDisplayName 用户显示名称
 * @property publicKeyAlgorithm COSE 算法标识（如 ES256 = -7, RS256 = -257）
 * @property publicKey 公钥（COSE 格式，Base64 编码）
 * @property privateKeyAlias Android Keystore 中的私钥别名
 * @property createdAt 创建时间戳
 * @property lastUsedAt 最后使用时间戳
 * @property useCount 使用次数
 * @property iconUrl 网站/应用图标 URL
 * @property isDiscoverable 是否为可发现凭据（Resident Key）
 * @property isUserVerificationRequired 是否需要用户验证（生物识别）
 * @property transports 支持的传输方式（internal, usb, nfc, ble）
 * @property aaguid 认证器 AAGUID
 * @property signCount 签名计数器（用于检测克隆攻击）
 * @property isBackedUp 是否已备份（用于 WebDAV 同步）
 * @property notes 用户备注
 */
@Entity(
    tableName = "passkeys",
    indices = [
        Index(value = ["credential_id"], name = "index_passkeys_credential_id"),
        Index(value = ["rp_id"], name = "index_passkeys_rp_id"),
        Index(value = ["user_name"], name = "index_passkeys_user_name"),
        Index(
            value = ["bitwarden_vault_id", "bitwarden_cipher_id"],
            name = "index_passkeys_bitwarden_vault_cipher"
        ),
        Index(
            value = ["bitwarden_vault_id", "bitwarden_cipher_id", "credential_id"],
            name = "index_passkeys_bitwarden_scope_credential"
        ),
        Index(
            value = ["keepass_database_id", "passkey_mode", "credential_id"],
            name = "index_passkeys_keepass_scope_credential",
            unique = true
        ),
        Index(value = ["mdbx_database_id"], name = "index_passkeys_mdbx_database_id"),
        Index(value = ["mdbx_database_id", "mdbx_folder_id"], name = "index_passkeys_mdbx_database_folder")
    ]
)
data class PasskeyEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "credential_id")
    val credentialId: String,
    
    @ColumnInfo(name = "rp_id")
    val rpId: String,
    
    @ColumnInfo(name = "rp_name")
    val rpName: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "user_name")
    val userName: String,
    
    @ColumnInfo(name = "user_display_name")
    val userDisplayName: String,
    
    @ColumnInfo(name = "public_key_algorithm")
    val publicKeyAlgorithm: Int = -7, // ES256
    
    @ColumnInfo(name = "public_key")
    val publicKey: String,
    
    @ColumnInfo(name = "private_key_alias")
    val privateKeyAlias: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,
    
    @ColumnInfo(name = "icon_url")
    val iconUrl: String? = null,
    
    @ColumnInfo(name = "is_discoverable")
    val isDiscoverable: Boolean = true,
    
    @ColumnInfo(name = "is_user_verification_required")
    val isUserVerificationRequired: Boolean = true,
    
    @ColumnInfo(name = "transports")
    val transports: String = "internal", // 逗号分隔的传输方式
    
    @ColumnInfo(name = "aaguid")
    val aaguid: String = "",
    
    @ColumnInfo(name = "sign_count")
    val signCount: Long = 0,
    
    @ColumnInfo(name = "is_backed_up")
    val isBackedUp: Boolean = false,
    
    @ColumnInfo(name = "notes")
    val notes: String = "",

    // 绑定的密码条目（可为空，支持后期绑定）
    @ColumnInfo(name = "bound_password_id", defaultValue = "NULL")
    val boundPasswordId: Long? = null,

    // 统一文件夹归属（复用本地分类体系）
    @ColumnInfo(name = "category_id", defaultValue = "NULL")
    val categoryId: Long? = null,

    // 归属的 KeePass 数据库（用于统一目标存储选择）
    @ColumnInfo(name = "keepass_database_id", defaultValue = "NULL")
    val keepassDatabaseId: Long? = null,

    // KeePass 分组路径（为空表示数据库根目录）
    @ColumnInfo(name = "keepass_group_path", defaultValue = "NULL")
    val keepassGroupPath: String? = null,

    // MDBX project-centric 数据库归属
    @ColumnInfo(name = "mdbx_database_id", defaultValue = "NULL")
    val mdbxDatabaseId: Long? = null,
    @ColumnInfo(name = "mdbx_folder_id", defaultValue = "NULL")
    val mdbxFolderId: String? = null,
    
    // Bitwarden 同步字段（仅同步元数据，私钥无法导出）
    @ColumnInfo(name = "bitwarden_vault_id", defaultValue = "NULL")
    val bitwardenVaultId: Long? = null,           // 关联的 Bitwarden Vault

    // Bitwarden 文件夹 ID（为空表示 Vault 根目录）
    @ColumnInfo(name = "bitwarden_folder_id", defaultValue = "NULL")
    val bitwardenFolderId: String? = null,
    
    @ColumnInfo(name = "bitwarden_cipher_id", defaultValue = "NULL")
    val bitwardenCipherId: String? = null,        // Bitwarden Cipher UUID
    
    @ColumnInfo(name = "sync_status", defaultValue = "NONE")
    val syncStatus: String = "NONE",              // 同步状态: NONE, PENDING, SYNCING, SYNCED, FAILED

    // Passkey 模式:
    // LEGACY         -> 旧 Monica 通行密钥（保留本地兼容，不参与 Bitwarden 可用同步）
    // BW_COMPAT      -> Bitwarden 兼容模式（可参与 Bitwarden/Keyguard 同步）
    // KEEPASS_COMPAT -> KeePassDX/KeePassXC KPEX_PASSKEY_* 兼容格式（可与 Monica 写入/回读的 KDBX 互通）
    @ColumnInfo(name = "passkey_mode", defaultValue = "'LEGACY'")
    val passkeyMode: String = MODE_LEGACY,

    // WebAuthn creation/signing metadata, independent of the local isBackedUp sync marker.
    // NULL keeps the historical Android BE=true/BS=true behavior for existing credentials.
    @ColumnInfo(name = "backup_eligible", defaultValue = "NULL")
    val backupEligible: Boolean? = null,
    @ColumnInfo(name = "backup_state", defaultValue = "NULL")
    val backupState: Boolean? = null
) {
    /**
     * 获取传输方式列表
     */
    fun getTransportsList(): List<String> = transports.split(",").filter { it.isNotBlank() }
    
    /**
     * 获取算法名称
     */
    fun getAlgorithmName(): String = when (publicKeyAlgorithm) {
        -7 -> "ES256 (ECDSA P-256)"
        -257 -> "RS256 (RSA PKCS#1)"
        -37 -> "PS256 (RSA PSS)"
        -8 -> "EdDSA (Ed25519)"
        else -> "Unknown ($publicKeyAlgorithm)"
    }
    
    companion object {
        // Passkey 模式
        const val MODE_LEGACY = "LEGACY"
        const val MODE_BW_COMPAT = "BW_COMPAT"
        const val MODE_KEEPASS_COMPAT = "KEEPASS_COMPAT"

        // COSE 算法常量
        const val ALGORITHM_ES256 = -7
        const val ALGORITHM_RS256 = -257
        const val ALGORITHM_PS256 = -37
        const val ALGORITHM_EDDSA = -8
        
        // 传输方式常量
        const val TRANSPORT_INTERNAL = "internal"
        const val TRANSPORT_USB = "usb"
        const val TRANSPORT_NFC = "nfc"
        const val TRANSPORT_BLE = "ble"
        const val TRANSPORT_HYBRID = "hybrid"
    }

    fun isBitwardenCompatible(): Boolean = passkeyMode == MODE_BW_COMPAT

    fun isKeePassCompatible(): Boolean = passkeyMode == MODE_KEEPASS_COMPAT

    fun hasPersistentId(): Boolean = id > 0L

    /** Authentication surfaces show account identity without disclosing vault notes. */
    fun authenticationTitle(): String = userDisplayName.trim()
        .ifBlank { userName.trim() }
        .ifBlank { rpName.trim() }
        .ifBlank { rpId.trim() }

    /** Monica 展示名称：用户备注优先，不改写 WebAuthn 原始名称。 */
    fun displayTitle(): String = notes.trim()
        .ifBlank { userDisplayName.trim() }
        .ifBlank { userName.trim() }
        .ifBlank { rpName.trim() }
        .ifBlank { rpId.trim() }
}

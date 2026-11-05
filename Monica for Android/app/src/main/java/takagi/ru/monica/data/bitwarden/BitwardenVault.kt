package takagi.ru.monica.data.bitwarden

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Bitwarden Vault Entity - 存储用户的 Bitwarden 账户信息
 * 
 * 设计原则:
 * 1. 每个 Vault 对应一个 Bitwarden 账户
 * 2. 敏感数据 (refreshToken, masterKey, encKey, macKey) 必须加密存储
 * 3. 服务器端点支持官方服务和自托管服务
 * 
 * 安全规则:
 * - 任何错误都不能导致此表数据丢失
 * - 删除操作需要软删除机制
 */
@Entity(
    tableName = "bitwarden_vaults",
    indices = [
        Index(name = "index_bitwarden_vaults_account_key", value = ["account_key"], unique = true),
        Index(name = "index_bitwarden_vaults_canonical_email", value = ["canonical_email"]),
        Index(name = "index_bitwarden_vaults_is_default", value = ["is_default"])
    ]
)
data class BitwardenVault(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // === 账户标识 ===
    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "canonical_email", defaultValue = "''")
    val canonicalEmail: String = "",
    
    @ColumnInfo(name = "user_id")
    val userId: String? = null,  // Bitwarden 用户 UUID

    @ColumnInfo(name = "account_key", defaultValue = "''")
    val accountKey: String = "",
    
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
    
    // === 服务器配置 ===
    @ColumnInfo(name = "server_url")
    val serverUrl: String = "https://vault.bitwarden.com",
    
    @ColumnInfo(name = "identity_url")
    val identityUrl: String = "https://identity.bitwarden.com",
    
    @ColumnInfo(name = "api_url")
    val apiUrl: String = "https://api.bitwarden.com",
    
    @ColumnInfo(name = "events_url")
    val eventsUrl: String? = null,

    // Explicit transport settings. Null/empty values preserve legacy resolution until a save.
    @ColumnInfo(name = "environment_preset")
    val environmentPreset: String? = null,

    @ColumnInfo(name = "web_vault_url")
    val webVaultUrl: String? = null,

    @ColumnInfo(name = "notifications_url")
    val notificationsUrl: String? = null,

    @ColumnInfo(name = "transport_revision", defaultValue = "''")
    val transportRevision: String = "",

    // Reference only. Proxy headers and new TLS secrets live in protected preferences.
    @ColumnInfo(name = "transport_secret_ref")
    val transportSecretRef: String? = null,

    // === TLS / 证书配置（仅用于自托管）===
    @ColumnInfo(name = "tls_certificate_alias")
    val tlsCertificateAlias: String? = null,

    @ColumnInfo(name = "tls_ca_certificate_pem")
    val tlsCaCertificatePem: String? = null,

    @ColumnInfo(name = "tls_mtls_enabled", defaultValue = "0")
    val tlsMtlsEnabled: Boolean = false,

    @ColumnInfo(name = "tls_client_cert_pkcs12")
    val tlsClientCertPkcs12Base64: String? = null,

    @ColumnInfo(name = "tls_encrypted_client_cert_password")
    val tlsEncryptedClientCertPassword: String? = null,
    
    // === 认证信息 (加密存储) ===
    @ColumnInfo(name = "encrypted_access_token")
    val encryptedAccessToken: String? = null,  // 访问令牌 (加密)
    
    @ColumnInfo(name = "encrypted_refresh_token")
    val encryptedRefreshToken: String? = null, // 刷新令牌 (加密)
    
    @ColumnInfo(name = "access_token_expires_at")
    val accessTokenExpiresAt: Long? = null,    // 访问令牌过期时间 (Unix ms)
    
    // === 加密密钥 (加密存储) ===
    @ColumnInfo(name = "encrypted_master_key")
    val encryptedMasterKey: String? = null,    // 主密钥 (Base64, 加密)
    
    @ColumnInfo(name = "encrypted_enc_key")
    val encryptedEncKey: String? = null,       // 加密密钥 (32字节, 加密)
    
    @ColumnInfo(name = "encrypted_mac_key")
    val encryptedMacKey: String? = null,       // MAC 密钥 (32字节, 加密)
    
    // === KDF 配置 ===
    @ColumnInfo(name = "kdf_type")
    val kdfType: Int = KDF_TYPE_PBKDF2,        // 0=PBKDF2, 1=Argon2id
    
    @ColumnInfo(name = "kdf_iterations")
    val kdfIterations: Int = 600000,           // PBKDF2: 600000, Argon2: 3
    
    @ColumnInfo(name = "kdf_memory")
    val kdfMemory: Int? = null,                // Argon2 专用: 64 (MB)
    
    @ColumnInfo(name = "kdf_parallelism")
    val kdfParallelism: Int? = null,           // Argon2 专用: 4
    
    // === 同步状态 ===
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null,              // 最后同步时间 (Unix ms)
    
    @ColumnInfo(name = "last_full_sync_at")
    val lastFullSyncAt: Long? = null,          // 最后完整同步时间
    
    @ColumnInfo(name = "revision_date")
    val revisionDate: String? = null,          // 服务器 revision date
    
    // === 状态标志 ===
    @ColumnInfo(name = "is_default", defaultValue = "0")
    val isDefault: Boolean = false,            // 是否为默认 vault
    
    @ColumnInfo(name = "is_locked", defaultValue = "1")
    val isLocked: Boolean = true,              // 是否已锁定
    
    @ColumnInfo(name = "is_connected", defaultValue = "0")
    val isConnected: Boolean = false,          // 是否已连接
    
    @ColumnInfo(name = "sync_enabled", defaultValue = "1")
    val syncEnabled: Boolean = true,           // 是否启用同步
    
    // === 审计字段 ===
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KDF_TYPE_PBKDF2 = 0
        const val KDF_TYPE_ARGON2ID = 1
        
        const val DEFAULT_PBKDF2_ITERATIONS = 600000
        const val DEFAULT_ARGON2_ITERATIONS = 3
        const val DEFAULT_ARGON2_MEMORY = 64
        const val DEFAULT_ARGON2_PARALLELISM = 4
    }
    
    /**
     * 检查访问令牌是否过期
     */
    fun isAccessTokenExpired(): Boolean {
        val expiresAt = accessTokenExpiresAt ?: return true
        // 提前 5 分钟刷新
        return System.currentTimeMillis() > (expiresAt - 5 * 60 * 1000)
    }
    
    /**
     * 是否使用 Argon2 KDF
     */
    fun usesArgon2(): Boolean = kdfType == KDF_TYPE_ARGON2ID
}

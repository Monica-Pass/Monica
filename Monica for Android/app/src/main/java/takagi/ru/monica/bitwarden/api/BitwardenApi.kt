@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package takagi.ru.monica.bitwarden.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import retrofit2.Response
import retrofit2.http.*

/**
 * Bitwarden Identity API - 认证和令牌管理
 * 
 * 端点: https://identity.bitwarden.com (官方)
 *       或自托管服务的 /identity 路径
 */
interface BitwardenIdentityApi {
    
    /**
     * 获取预登录信息 (KDF 类型和参数)
     */
    @POST("accounts/prelogin")
    suspend fun preLogin(
        @Body request: PreLoginRequest
    ): Response<PreLoginResponse>
    
    /**
     * 登录获取访问令牌
     * 
     * 使用 Resource Owner Password Grant
     * 参考 keyguard: 需要 Auth-Email header (Base64 编码的邮箱)
     * 
     * 完全模拟 Keyguard 的 Linux Desktop 模式
     * deviceType: 8 = Linux
     * client_id: desktop
     */
    @FormUrlEncoded
    @POST("connect/token")
    suspend fun login(
        @Header("Auth-Email") authEmail: String,      // URL-safe Base64 编码的邮箱 (重要!)
        @Header("device-type") deviceTypeHeader: String = "8",  // 8 = Linux (与 Keyguard 一致)
        @Header("cache-control") cacheControl: String = "no-store",
        @Header("Bitwarden-Client-Name") clientName: String = "desktop",
        @Header("Bitwarden-Client-Version") clientVersion: String = "2025.9.1",  // 与 Keyguard 一致
        @Field("grant_type") grantType: String = "password",
        @Field("username") username: String,
        @Field("password") passwordHash: String,  // 标准 Base64 编码的 Master Password Hash
        @Field("scope") scope: String = "api offline_access",
        @Field("client_id") clientId: String = "desktop",  // 使用 desktop (与 Keyguard 一致)
        @Field("captchaResponse") captchaResponse: String? = null,
        @Field("deviceIdentifier") deviceIdentifier: String,
        @Field("deviceType") deviceType: String = "8",  // 8 = Linux (与 Keyguard 一致)
        @Field("deviceName") deviceName: String = "linux"  // 与 Keyguard 一致
    ): Response<TokenResponse>
    
    /**
     * 刷新访问令牌
     */
    @FormUrlEncoded
    @POST("connect/token")
    suspend fun refreshToken(
        @Header("Bitwarden-Client-Name") clientName: String = "desktop",
        @Header("Bitwarden-Client-Version") clientVersion: String = "2025.9.1",
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String = "desktop"
    ): Response<TokenResponse>
    
    /**
     * 两步验证登录
     */
    @FormUrlEncoded
    @POST("connect/token")
    suspend fun loginTwoFactor(
        @Header("Auth-Email") authEmail: String,
        @Header("device-type") deviceTypeHeader: String = "8",  // 8 = Linux (与 Keyguard 一致)
        @Header("cache-control") cacheControl: String = "no-store",
        @Header("Bitwarden-Client-Name") clientName: String = "desktop",
        @Header("Bitwarden-Client-Version") clientVersion: String = "2025.9.1",  // 与 Keyguard 一致
        @Field("grant_type") grantType: String = "password",
        @Field("username") username: String,
        @Field("password") passwordHash: String,
        @Field("scope") scope: String = "api offline_access",
        @Field("client_id") clientId: String = "desktop",  // 与 Keyguard 一致
        @Field("captchaResponse") captchaResponse: String? = null,
        @Field("deviceIdentifier") deviceIdentifier: String,
        @Field("deviceType") deviceType: String = "8",  // 8 = Linux (与 Keyguard 一致)
        @Field("deviceName") deviceName: String = "linux",  // 与 Keyguard 一致
        @Field("twoFactorToken") twoFactorToken: String,
        @Field("twoFactorProvider") twoFactorProvider: Int,
        @Field("twoFactorRemember") twoFactorRemember: Int = 0
    ): Response<TokenResponse>

    /**
     * 新设备验证（Email New Device OTP）
     */
    @FormUrlEncoded
    @POST("connect/token")
    suspend fun loginNewDeviceOtp(
        @Header("Auth-Email") authEmail: String,
        @Header("device-type") deviceTypeHeader: String = "8",  // 8 = Linux (与 Keyguard 一致)
        @Header("cache-control") cacheControl: String = "no-store",
        @Header("Bitwarden-Client-Name") clientName: String = "desktop",
        @Header("Bitwarden-Client-Version") clientVersion: String = "2025.9.1",
        @Field("grant_type") grantType: String = "password",
        @Field("username") username: String,
        @Field("password") passwordHash: String,
        @Field("scope") scope: String = "api offline_access",
        @Field("client_id") clientId: String = "desktop",
        @Field("captchaResponse") captchaResponse: String? = null,
        @Field("deviceIdentifier") deviceIdentifier: String,
        @Field("deviceType") deviceType: String = "8",
        @Field("deviceName") deviceName: String = "linux",
        @Field("newDeviceOtp") newDeviceOtp: String
    ): Response<TokenResponse>
}

/**
 * Bitwarden Vault API - 密码库数据操作
 * 
 * 端点: https://api.bitwarden.com (官方)
 *       或自托管服务的 /api 路径
 */
interface BitwardenVaultApi {
    @POST("two-factor/send-email-login")
    suspend fun sendTwoFactorEmailLogin(
        @Body request: SendEmailLoginRequest
    ): Response<Unit>

    /**
     * 同步全部数据
     * 
     * 返回所有 ciphers, folders, collections, policies 等
     */
    @GET("sync")
    suspend fun sync(
        @Header("Authorization") authorization: String,
        @Query("excludeDomains") excludeDomains: Boolean = true
    ): Response<SyncResponse>
    
    /**
     * 获取单个 Cipher
     */
    @GET("ciphers/{id}")
    suspend fun getCipher(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String
    ): Response<CipherApiResponse>

    // Preserve unknown encrypted fields when updating a credential within an existing login.
    @GET("ciphers/{id}")
    suspend fun getCipherDocument(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String
    ): Response<kotlinx.serialization.json.JsonObject>

    @PUT("ciphers/{id}")
    suspend fun updateCipherDocument(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Body cipher: kotlinx.serialization.json.JsonObject
    ): Response<kotlinx.serialization.json.JsonObject>
    
    /**
     * 创建 Cipher
     */
    @POST("ciphers")
    suspend fun createCipher(
        @Header("Authorization") authorization: String,
        @Body cipher: CipherCreateRequest
    ): Response<CipherApiResponse>
    
    /**
     * 更新 Cipher
     */
    @PUT("ciphers/{id}")
    suspend fun updateCipher(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Body cipher: CipherUpdateRequest
    ): Response<CipherApiResponse>
    
    /**
     * 删除 Cipher (软删除到回收站)
     */
    @PUT("ciphers/{id}/delete")
    suspend fun deleteCipher(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String
    ): Response<Unit>
    
    /**
     * 永久删除 Cipher
     */
    @DELETE("ciphers/{id}")
    suspend fun permanentDeleteCipher(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String
    ): Response<Unit>
    
    /**
     * 恢复已删除的 Cipher
     */
    @PUT("ciphers/{id}/restore")
    suspend fun restoreCipher(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String
    ): Response<CipherApiResponse>
    
    // ========== Folder 操作 ==========
    
    /**
     * 获取所有文件夹
     */
    @GET("folders")
    suspend fun getFolders(
        @Header("Authorization") authorization: String
    ): Response<FoldersResponse>
    
    /**
     * 创建文件夹
     */
    @POST("folders")
    suspend fun createFolder(
        @Header("Authorization") authorization: String,
        @Body folder: FolderCreateRequest
    ): Response<FolderApiResponse>
    
    /**
     * 更新文件夹
     */
    @PUT("folders/{id}")
    suspend fun updateFolder(
        @Header("Authorization") authorization: String,
        @Path("id") folderId: String,
        @Body folder: FolderUpdateRequest
    ): Response<FolderApiResponse>
    
    /**
     * 删除文件夹
     */
    @DELETE("folders/{id}")
    suspend fun deleteFolder(
        @Header("Authorization") authorization: String,
        @Path("id") folderId: String
    ): Response<Unit>

    // ========== Attachment 操作 ==========

    /**
     * 创建附件上传凭据（v2 端点）。
     *
     * 返回值根据 `fileUploadType` 字段指示后续如何上传密文：
     * - 0 = Direct：需要走 [uploadAttachmentDirect] 以 Multipart 发送；
     * - 1 = Azure：需要 PUT 到 `url`，请求头带 `x-ms-blob-type: BlockBlob`。
     *
     * 对应 Bitwarden 官方 `/ciphers/{id}/attachment/v2`。
     */
    @POST("ciphers/{id}/attachment/v2")
    suspend fun createAttachmentUploadUrl(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Body request: AttachmentUploadRequest
    ): Response<AttachmentUploadResponse>

    /**
     * Direct 模式上传附件密文（fileUploadType = 0）。
     *
     * Multipart 需要两个 part：
     * - `key`：附件独立密钥（EncString）；
     * - `data`：加密后的文件字节。
     */
    @Multipart
    @POST("ciphers/{id}/attachment/{attachmentId}")
    suspend fun uploadAttachmentDirect(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Path("attachmentId") attachmentId: String,
        @Part key: okhttp3.MultipartBody.Part,
        @Part data: okhttp3.MultipartBody.Part
    ): Response<Unit>

    /**
     * 获取附件下载信息。返回值里 `url` 通常是一个短期有效的签名 URL。
     */
    @GET("ciphers/{id}/attachment/{attachmentId}")
    suspend fun getAttachmentDownload(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Path("attachmentId") attachmentId: String
    ): Response<AttachmentDownloadInfo>

    /**
     * 删除附件。接口成功后再清理本地记录与密文缓存。
     */
    @DELETE("ciphers/{id}/attachment/{attachmentId}")
    suspend fun deleteAttachment(
        @Header("Authorization") authorization: String,
        @Path("id") cipherId: String,
        @Path("attachmentId") attachmentId: String
    ): Response<Unit>

    // ========== Send 操作 ==========

    /**
     * 创建 Send（目前主要用于文本 Send）
     */
    @POST("sends")
    suspend fun createSend(
        @Header("Authorization") authorization: String,
        @Body send: SendCreateRequest
    ): Response<SendApiResponse>

    /**
     * 创建文件 Send，并返回后续文件密文上传凭据。
     */
    @POST("sends/file/v2")
    suspend fun createFileSend(
        @Header("Authorization") authorization: String,
        @Body send: SendCreateRequest
    ): Response<SendFileUploadDataResponse>

    /**
     * Direct 模式上传文件 Send 密文。
     */
    @Multipart
    @POST("sends/{id}/file/{fileId}")
    suspend fun uploadSendFileDirect(
        @Header("Authorization") authorization: String,
        @Path("id") sendId: String,
        @Path("fileId") fileId: String,
        @Part data: okhttp3.MultipartBody.Part
    ): Response<Unit>

    /**
     * 获取单个 Send
     */
    @GET("sends/{id}")
    suspend fun getSend(
        @Header("Authorization") authorization: String,
        @Path("id") sendId: String
    ): Response<SendApiResponse>

    /**
     * 更新 Send
     */
    @PUT("sends/{id}")
    suspend fun updateSend(
        @Header("Authorization") authorization: String,
        @Path("id") sendId: String,
        @Body send: SendCreateRequest
    ): Response<SendApiResponse>

    /**
     * 删除 Send
     */
    @DELETE("sends/{id}")
    suspend fun deleteSend(
        @Header("Authorization") authorization: String,
        @Path("id") sendId: String
    ): Response<Unit>
}

// ========== 请求/响应数据模型 ==========

@Serializable
data class PreLoginRequest(
    val email: String
)

@Serializable
data class SendEmailLoginRequest(
    @SerialName("deviceIdentifier")
    val deviceIdentifier: String,
    @SerialName("email")
    val email: String,
    @SerialName("masterPasswordHash")
    val masterPasswordHash: String
)

/**
 * PreLogin 响应
 * 
 * 使用 @JsonNames 兼容服务器返回的不同大小写
 * 使用默认值防止服务器未返回某些字段时崩溃
 */
@Serializable
data class PreLoginResponse(
    @JsonNames("kdf")
    @SerialName("Kdf")
    val kdf: Int = 0,                    // 0=PBKDF2, 1=Argon2id
    @JsonNames("kdfIterations")
    @SerialName("KdfIterations")
    val kdfIterations: Int = 600000,     // PBKDF2 默认迭代次数
    @JsonNames("kdfMemory")
    @SerialName("KdfMemory")
    val kdfMemory: Int? = null,          // Argon2 专用
    @JsonNames("kdfParallelism")
    @SerialName("KdfParallelism")
    val kdfParallelism: Int? = null      // Argon2 专用
)

/**
 * Token 响应
 * 
 * 登录成功后返回的令牌和密钥信息
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String = "",
    @SerialName("expires_in")
    val expiresIn: Int = 3600,       // 秒数
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,          // Protected Symmetric Key (加密)
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String? = null,   // 私钥 (加密)
    @JsonNames("kdf")
    @SerialName("Kdf")
    val kdf: Int? = null,
    @JsonNames("kdfIterations")
    @SerialName("KdfIterations")
    val kdfIterations: Int? = null,
    @JsonNames("kdfMemory")
    @SerialName("KdfMemory")
    val kdfMemory: Int? = null,
    @JsonNames("kdfParallelism")
    @SerialName("KdfParallelism")
    val kdfParallelism: Int? = null,
    @JsonNames("twoFactorToken")
    @SerialName("TwoFactorToken")
    val twoFactorToken: String? = null,
    // 两步验证相关
    @SerialName("error")
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @JsonNames("errorModel")
    @SerialName("ErrorModel")
    val errorModel: ErrorModel? = null,
    @JsonNames("HCaptcha_SiteKey", "hCaptcha_SiteKey")
    @SerialName("HCaptcha_SiteKey")
    val hCaptchaSiteKey: String? = null,
    @JsonNames("twoFactorProviders")
    @SerialName("TwoFactorProviders")
    val twoFactorProviders: List<String>? = null,
    @JsonNames("twoFactorProviders2")
    @SerialName("TwoFactorProviders2")
    val twoFactorProviders2: Map<String, JsonElement>? = null,
    @JsonNames("resetMasterPassword")
    @SerialName("ResetMasterPassword")
    val resetMasterPassword: Boolean? = null,
    @SerialName("scope")
    val scope: String? = null
)

@Serializable
data class ErrorModel(
    @JsonNames("message")
    @SerialName("Message")
    val message: String? = null
)

@Serializable
data class SyncResponse(
    @JsonNames("profile")
    @SerialName("Profile")
    val profile: ProfileResponse,
    @JsonNames("folders")
    @SerialName("Folders")
    val folders: List<FolderApiResponse> = emptyList(),
    @JsonNames("ciphers")
    @SerialName("Ciphers")
    val ciphers: List<CipherApiResponse> = emptyList(),
    @JsonNames("collections")
    @SerialName("Collections")
    val collections: List<CollectionResponse>? = null,
    @JsonNames("policies")
    @SerialName("Policies")
    val policies: List<PolicyResponse>? = null,
    @JsonNames("sends")
    @SerialName("Sends")
    val sends: List<SendApiResponse>? = null
)

@Serializable
data class ProfileResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("email")
    @SerialName("Email")
    val email: String = "",
    @JsonNames("premium")
    @SerialName("Premium")
    val premium: Boolean = false,
    @JsonNames("premiumFromOrganization")
    @SerialName("PremiumFromOrganization")
    val premiumFromOrganization: Boolean = false,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String? = null,
    @JsonNames("securityStamp")
    @SerialName("SecurityStamp")
    val securityStamp: String? = null
) {
    /** 与官方 Bitwarden 客户端一致：premium 或 premiumFromOrganization 任一为 true 即视为 Premium。 */
    val hasPremium: Boolean get() = premium || premiumFromOrganization
}

@Serializable
data class FolderApiResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,       // 加密的名称
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: String = ""
)

@Serializable
data class FoldersResponse(
    @JsonNames("data")
    @SerialName("Data")
    val data: List<FolderApiResponse> = emptyList()
)

@Serializable
data class CipherApiResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("organizationId")
    @SerialName("OrganizationId")
    val organizationId: String? = null,
    @JsonNames("folderId")
    @SerialName("FolderId")
    val folderId: String? = null,
    @JsonNames("type")
    @SerialName("Type")
    val type: Int = 1,
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("login")
    @SerialName("Login")
    val login: CipherLoginApiData? = null,
    @JsonNames("card")
    @SerialName("Card")
    val card: CipherCardApiData? = null,
    @JsonNames("identity")
    @SerialName("Identity")
    val identity: CipherIdentityApiData? = null,
    @JsonNames("secureNote")
    @SerialName("SecureNote")
    val secureNote: CipherSecureNoteApiData? = null,
    @JsonNames("SshKey", "SSHKey", "ssh_key")
    @SerialName("sshKey")
    val sshKey: CipherSshKeyApiData? = null,
    @JsonNames("fields")
    @SerialName("Fields")
    val fields: List<CipherFieldApiData>? = null,
    @JsonNames("favorite")
    @SerialName("Favorite")
    val favorite: Boolean = false,
    @JsonNames("reprompt")
    @SerialName("Reprompt")
    val reprompt: Int = 0,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: String = "",
    @JsonNames("creationDate")
    @SerialName("CreationDate")
    val creationDate: String? = null,
    @JsonNames("archivedDate")
    @SerialName("ArchivedDate")
    val archivedDate: String? = null,
    @JsonNames("deletedDate")
    @SerialName("DeletedDate")
    val deletedDate: String? = null,
    @JsonNames("attachments")
    @SerialName("Attachments")
    val attachments: List<CipherAttachmentApiData>? = null
)

@Serializable
data class CipherLoginApiData(
    @JsonNames("Username", "username")
    @SerialName("username")
    val username: String? = null,
    @JsonNames("Password", "password")
    @SerialName("password")
    val password: String? = null,
    @JsonNames("PasswordRevisionDate", "passwordRevisionDate")
    @SerialName("passwordRevisionDate")
    val passwordRevisionDate: String? = null,
    @JsonNames("Totp", "totp")
    @SerialName("totp")
    val totp: String? = null,
    @JsonNames("Uris", "uris")
    @SerialName("uris")
    val uris: List<CipherUriApiData>? = null,
    @JsonNames("Fido2Credentials", "fido2Credentials")
    @SerialName("fido2Credentials")
    val fido2Credentials: List<CipherLoginFido2CredentialApiData>? = null
)

@Serializable
data class CipherLoginFido2CredentialApiData(
    @JsonNames("CredentialId", "credentialId")
    @SerialName("credentialId")
    val credentialId: String? = null,
    @JsonNames("KeyType", "keyType")
    @SerialName("keyType")
    val keyType: String? = null,
    @JsonNames("KeyAlgorithm", "keyAlgorithm")
    @SerialName("keyAlgorithm")
    val keyAlgorithm: String? = null,
    @JsonNames("KeyCurve", "keyCurve")
    @SerialName("keyCurve")
    val keyCurve: String? = null,
    @JsonNames("KeyValue", "keyValue")
    @SerialName("keyValue")
    val keyValue: String? = null,
    @JsonNames("RpId", "rpId")
    @SerialName("rpId")
    val rpId: String? = null,
    @JsonNames("RpName", "rpName")
    @SerialName("rpName")
    val rpName: String? = null,
    @JsonNames("Counter", "counter")
    @SerialName("counter")
    val counter: String? = null,
    @JsonNames("UserHandle", "userHandle")
    @SerialName("userHandle")
    val userHandle: String? = null,
    @JsonNames("UserName", "userName")
    @SerialName("userName")
    val userName: String? = null,
    @JsonNames("UserDisplayName", "userDisplayName")
    @SerialName("userDisplayName")
    val userDisplayName: String? = null,
    @JsonNames("Discoverable", "discoverable")
    @SerialName("discoverable")
    val discoverable: String? = null,
    @JsonNames("CreationDate", "creationDate")
    @SerialName("creationDate")
    val creationDate: String? = null
)

@Serializable
data class CipherUriApiData(
    @JsonNames("Uri", "uri")
    @SerialName("uri")
    val uri: String? = null,
    @JsonNames("Match", "match")
    @SerialName("match")
    val match: Int? = null
)

@Serializable
data class CipherCardApiData(
    @JsonNames("CardholderName", "cardholderName")
    @SerialName("cardholderName")
    val cardholderName: String? = null,
    @JsonNames("Brand", "brand")
    @SerialName("brand")
    val brand: String? = null,
    @JsonNames("Number", "number")
    @SerialName("number")
    val number: String? = null,
    @JsonNames("ExpMonth", "expMonth")
    @SerialName("expMonth")
    val expMonth: String? = null,
    @JsonNames("ExpYear", "expYear")
    @SerialName("expYear")
    val expYear: String? = null,
    @JsonNames("Code", "code")
    @SerialName("code")
    val code: String? = null
)

@Serializable
data class CipherIdentityApiData(
    @JsonNames("Title", "title")
    @SerialName("title")
    val title: String? = null,
    @JsonNames("FirstName", "firstName")
    @SerialName("firstName")
    val firstName: String? = null,
    @JsonNames("MiddleName", "middleName")
    @SerialName("middleName")
    val middleName: String? = null,
    @JsonNames("LastName", "lastName")
    @SerialName("lastName")
    val lastName: String? = null,
    @JsonNames("address1")
    @SerialName("Address1")
    val address1: String? = null,
    @JsonNames("address2")
    @SerialName("Address2")
    val address2: String? = null,
    @JsonNames("address3")
    @SerialName("Address3")
    val address3: String? = null,
    @JsonNames("City", "city")
    @SerialName("city")
    val city: String? = null,
    @JsonNames("State", "state")
    @SerialName("state")
    val state: String? = null,
    @JsonNames("PostalCode", "postalCode")
    @SerialName("postalCode")
    val postalCode: String? = null,
    @JsonNames("Country", "country")
    @SerialName("country")
    val country: String? = null,
    @JsonNames("Company", "company")
    @SerialName("company")
    val company: String? = null,
    @JsonNames("Email", "email")
    @SerialName("email")
    val email: String? = null,
    @JsonNames("Phone", "phone")
    @SerialName("phone")
    val phone: String? = null,
    @JsonNames("SSN", "ssn")
    @SerialName("ssn")
    val ssn: String? = null,
    @JsonNames("Username", "username")
    @SerialName("username")
    val username: String? = null,
    @JsonNames("PassportNumber", "passportNumber")
    @SerialName("passportNumber")
    val passportNumber: String? = null,
    @JsonNames("LicenseNumber", "licenseNumber")
    @SerialName("licenseNumber")
    val licenseNumber: String? = null
)

@Serializable
data class CipherSecureNoteApiData(
    @JsonNames("Type", "type")
    @SerialName("type")
    val type: Int = 0
)

@Serializable
data class CipherSshKeyApiData(
    @JsonNames("PrivateKey", "privateKey", "private_key")
    @SerialName("privateKey")
    val privateKey: String? = null,
    @JsonNames("PublicKey", "publicKey", "public_key")
    @SerialName("publicKey")
    val publicKey: String? = null,
    @JsonNames("KeyFingerprint", "keyFingerprint", "Fingerprint", "fingerprint", "key_fingerprint")
    @SerialName("keyFingerprint")
    val keyFingerprint: String? = null
)

@Serializable
data class CipherFieldApiData(
    @JsonNames("Name", "name")
    @SerialName("name")
    val name: String? = null,
    @JsonNames("Value", "value")
    @SerialName("value")
    val value: String? = null,
    @JsonNames("Type", "type")
    @SerialName("type")
    val type: Int = 0,
    @JsonNames("LinkedId", "linkedId")
    @SerialName("linkedId")
    val linkedId: Int? = null
)

@Serializable
data class CollectionResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null
)

@Serializable
data class PolicyResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("type")
    @SerialName("Type")
    val type: Int = 0,
    @JsonNames("enabled")
    @SerialName("Enabled")
    val enabled: Boolean = false
)

@Serializable
data class SendApiResponse(
    @JsonNames("id")
    @SerialName("Id")
    val id: String = "",
    @JsonNames("accessId")
    @SerialName("AccessId")
    val accessId: String = "",
    @JsonNames("urlB64Key")
    @SerialName("UrlB64Key")
    val urlB64Key: String? = null,
    @JsonNames("key")
    @SerialName("Key")
    val key: String = "",
    @JsonNames("type")
    @SerialName("Type")
    val type: Int = 0, // 0=Text, 1=File
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("file")
    @SerialName("File")
    val file: SendFileApiData? = null,
    @JsonNames("text")
    @SerialName("Text")
    val text: SendTextApiData? = null,
    @JsonNames("accessCount")
    @SerialName("AccessCount")
    val accessCount: Int = 0,
    @JsonNames("maxAccessCount")
    @SerialName("MaxAccessCount")
    val maxAccessCount: Int? = null,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: String = "",
    @JsonNames("expirationDate")
    @SerialName("ExpirationDate")
    val expirationDate: String? = null,
    @JsonNames("deletionDate")
    @SerialName("DeletionDate")
    val deletionDate: String? = null,
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("disabled")
    @SerialName("Disabled")
    val disabled: Boolean = false,
    @JsonNames("hideEmail")
    @SerialName("HideEmail")
    val hideEmail: Boolean? = null
)

@Serializable
data class SendTextApiData(
    @JsonNames("text")
    @SerialName("Text")
    val text: String? = null,
    @JsonNames("hidden")
    @SerialName("Hidden")
    val hidden: Boolean? = null
)

@Serializable
data class SendFileApiData(
    @JsonNames("id")
    @SerialName("Id")
    val id: String? = null,
    @JsonNames("fileName")
    @SerialName("FileName")
    val fileName: String? = null,
    @JsonNames("size")
    @SerialName("Size")
    val size: String? = null,
    @JsonNames("sizeName")
    @SerialName("SizeName")
    val sizeName: String? = null,
    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null
)

@Serializable
data class SendFileUploadDataResponse(
    @JsonNames("fileUploadType")
    @SerialName("FileUploadType")
    val fileUploadType: Int = 0,
    @JsonNames("sendResponse")
    @SerialName("SendResponse")
    val sendResponse: SendApiResponse? = null,
    @JsonNames("url")
    @SerialName("Url")
    val url: String? = null
)

// ========== 创建/更新请求 ==========

@Serializable
data class CipherCreateRequest(
    @JsonNames("Type", "type")
    @SerialName("type")
    val type: Int,
    @JsonNames("FolderId", "folderId")
    @SerialName("folderId")
    val folderId: String? = null,
    @JsonNames("Name", "name")
    @SerialName("name")
    val name: String,          // 加密
    @JsonNames("Notes", "notes")
    @SerialName("notes")
    val notes: String? = null, // 加密
    @JsonNames("Login", "login")
    @SerialName("login")
    val login: CipherLoginApiData? = null,
    @JsonNames("Card", "card")
    @SerialName("card")
    val card: CipherCardApiData? = null,
    @JsonNames("Identity", "identity")
    @SerialName("identity")
    val identity: CipherIdentityApiData? = null,
    @JsonNames("SecureNote", "secureNote")
    @SerialName("secureNote")
    val secureNote: CipherSecureNoteApiData? = null,
    @JsonNames("SshKey", "sshKey")
    @SerialName("sshKey")
    val sshKey: CipherSshKeyApiData? = null,
    @JsonNames("Fields", "fields")
    @SerialName("fields")
    val fields: List<CipherFieldApiData>? = null,
    @JsonNames("Favorite", "favorite")
    @SerialName("favorite")
    val favorite: Boolean = false,
    @JsonNames("Reprompt", "reprompt")
    @SerialName("reprompt")
    val reprompt: Int = 0,
    @JsonNames("ArchivedDate", "archivedDate")
    @SerialName("archivedDate")
    val archivedDate: String? = null
)

@Serializable
data class CipherUpdateRequest(
    @JsonNames("Type", "type")
    @SerialName("type")
    val type: Int,
    @JsonNames("FolderId", "folderId")
    @SerialName("folderId")
    val folderId: String? = null,
    @JsonNames("Name", "name")
    @SerialName("name")
    val name: String,
    @JsonNames("Notes", "notes")
    @SerialName("notes")
    val notes: String? = null,
    @JsonNames("Login", "login")
    @SerialName("login")
    val login: CipherLoginApiData? = null,
    @JsonNames("Card", "card")
    @SerialName("card")
    val card: CipherCardApiData? = null,
    @JsonNames("Identity", "identity")
    @SerialName("identity")
    val identity: CipherIdentityApiData? = null,
    @JsonNames("SecureNote", "secureNote")
    @SerialName("secureNote")
    val secureNote: CipherSecureNoteApiData? = null,
    @JsonNames("SshKey", "sshKey")
    @SerialName("sshKey")
    val sshKey: CipherSshKeyApiData? = null,
    @JsonNames("Fields", "fields")
    @SerialName("fields")
    val fields: List<CipherFieldApiData>? = null,
    @JsonNames("Favorite", "favorite")
    @SerialName("favorite")
    val favorite: Boolean = false,
    @JsonNames("Reprompt", "reprompt")
    @SerialName("reprompt")
    val reprompt: Int = 0,
    @JsonNames("ArchivedDate", "archivedDate")
    @SerialName("archivedDate")
    val archivedDate: String? = null
)

@Serializable
data class FolderCreateRequest(
    @JsonNames("Name", "name")
    @SerialName("name")
    val name: String  // 加密
)

@Serializable
data class FolderUpdateRequest(
    @JsonNames("Name", "name")
    @SerialName("name")
    val name: String  // 加密
)

// ========== 附件 DTO ==========

/**
 * `/ciphers/{id}/attachment/v2` 请求体。
 *
 * 所有字段都由客户端在本地加密好后再提交：
 * - [key]：本次附件的独立密钥（EncString），用 cipher key 或 user key 包裹。
 * - [fileName]：文件名 EncString。
 * - [fileSize]：加密后的字节数（字符串形式，和服务端日志保持一致）。
 */
@Serializable
data class AttachmentUploadRequest(
    @JsonNames("key")
    @SerialName("key")
    val key: String,
    @JsonNames("fileName")
    @SerialName("fileName")
    val fileName: String,
    @JsonNames("fileSize")
    @SerialName("fileSize")
    val fileSize: String
)

/**
 * `/ciphers/{id}/attachment/v2` 响应体。
 */
@Serializable
data class AttachmentUploadResponse(
    /** 0 = Direct Multipart，1 = Azure Blob PUT。 */
    @JsonNames("fileUploadType")
    @SerialName("fileUploadType")
    val fileUploadType: Int = 0,
    /** Azure 下的预签名 URL，或 Direct 模式下服务器回显的占位 URL。 */
    @JsonNames("url")
    @SerialName("url")
    val url: String? = null,
    /** 最新的 Cipher 快照，方便客户端立刻更新本地记录。 */
    @JsonNames("cipherResponse")
    @SerialName("cipherResponse")
    val cipherResponse: CipherApiResponse? = null,
    /** 生成的附件元数据（`id`、`fileName` 等）。 */
    @JsonNames("attachmentId")
    @SerialName("attachmentId")
    val attachmentId: String? = null
)

/**
 * `/ciphers/{id}/attachment/{attachmentId}` GET 响应体。
 */
@Serializable
data class AttachmentDownloadInfo(
    @JsonNames("Id")
    @SerialName("id")
    val id: String = "",
    @JsonNames("Url")
    @SerialName("url")
    val url: String = "",
    @JsonNames("FileName")
    @SerialName("fileName")
    val fileName: String? = null,
    @JsonNames("Size")
    @SerialName("size")
    val size: String? = null,
    @JsonNames("Key")
    @SerialName("key")
    val key: String? = null
)

/**
 * `CipherApiResponse.attachments` 的元素。
 *
 * [size] 在 Bitwarden 官方返回中是字符串形式（与服务端日志格式一致）。
 */
@Serializable
data class CipherAttachmentApiData(
    @JsonNames("Id")
    @SerialName("id")
    val id: String = "",
    @JsonNames("FileName")
    @SerialName("fileName")
    val fileName: String? = null,
    @JsonNames("Size")
    @SerialName("size")
    val size: String = "0",
    @JsonNames("SizeName")
    @SerialName("sizeName")
    val sizeName: String? = null,
    @JsonNames("Key")
    @SerialName("key")
    val key: String? = null,
    @JsonNames("Url")
    @SerialName("url")
    val url: String? = null
)

@Serializable
data class SendCreateRequest(
    @SerialName("key")
    val key: String,
    @SerialName("type")
    val type: Int, // 0=Text, 1=File
    @SerialName("fileLength")
    val fileLength: Long? = null,
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String? = null,
    @SerialName("password")
    val password: String? = null,
    @SerialName("disabled")
    val disabled: Boolean = false,
    @SerialName("hideEmail")
    val hideEmail: Boolean = false,
    @SerialName("deletionDate")
    val deletionDate: String,
    @SerialName("expirationDate")
    val expirationDate: String? = null,
    @SerialName("maxAccessCount")
    val maxAccessCount: Int? = null,
    @SerialName("text")
    val text: SendTextCreateRequest? = null,
    @SerialName("file")
    val file: SendFileCreateRequest? = null
)

@Serializable
data class SendTextCreateRequest(
    @SerialName("text")
    val text: String,
    @SerialName("hidden")
    val hidden: Boolean = false
)

@Serializable
data class SendFileCreateRequest(
    @SerialName("fileName")
    val fileName: String? = null
)

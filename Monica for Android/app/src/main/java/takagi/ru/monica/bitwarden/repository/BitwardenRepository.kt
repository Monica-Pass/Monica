package takagi.ru.monica.bitwarden.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import takagi.ru.monica.attachments.facade.AttachmentUriMetadata
import takagi.ru.monica.attachments.facade.AttachmentSizeValidator
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.api.BitwardenApiFactory
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenLegacyEnvironmentSnapshot
import takagi.ru.monica.bitwarden.api.BitwardenTransportPolicy
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.bitwarden.api.BitwardenVaultEnvironmentStore
import takagi.ru.monica.bitwarden.api.toDraft
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.api.FolderCreateRequest
import takagi.ru.monica.bitwarden.api.FolderUpdateRequest
import takagi.ru.monica.bitwarden.BitwardenRestoreQueueOutcome
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.crypto.BitwardenKdfMemoryException
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper
import takagi.ru.monica.bitwarden.service.BitwardenAuthService
import takagi.ru.monica.bitwarden.service.BitwardenAuthSessionGate
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementInfo
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementState
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementResult
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationAction
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationBinding
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationCoordinator
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationHttpResult
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationIntentStore
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKind
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKdf
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationMetadata
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationOutcome
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationPhase
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationProtocol
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationRecord
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationSnapshot
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationStored
import takagi.ru.monica.bitwarden.service.BitwardenRememberedTwoFactorStore
import takagi.ru.monica.bitwarden.service.BitwardenSecurityStampPolicy
import takagi.ru.monica.bitwarden.service.BitwardenStampDecision
import takagi.ru.monica.bitwarden.api.ProfileResponse
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevice
import takagi.ru.monica.bitwarden.service.BitwardenDiagLogger
import takagi.ru.monica.bitwarden.service.BitwardenHistoricalTotpRepairResult
import takagi.ru.monica.bitwarden.service.BitwardenHistoricalTotpRepairService
import takagi.ru.monica.bitwarden.service.BitwardenAttachmentMetadataDecoder
import takagi.ru.monica.bitwarden.service.BitwardenCipherKeyResolver
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity
import takagi.ru.monica.bitwarden.service.BitwardenSendAuth
import takagi.ru.monica.bitwarden.service.BitwardenSendSubmission
import takagi.ru.monica.bitwarden.service.BitwardenSendCache
import takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore
import takagi.ru.monica.bitwarden.service.SendAuthAction
import takagi.ru.monica.bitwarden.service.BitwardenSendEdit
import takagi.ru.monica.bitwarden.service.SendPasswordAction
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.LoginResult
import takagi.ru.monica.bitwarden.service.classifyBitwardenLoginError
import takagi.ru.monica.bitwarden.service.SyncResult as ServiceSyncResult
import takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache

/**
 * Bitwarden 统一数据仓库
 * 
 * 职责：
 * 1. 管理 Bitwarden Vault 的生命周期（登录、登出、Token 刷新）
 * 2. 协调认证服务和同步服务
 * 3. 提供统一的数据访问接口
 * 4. 管理加密密钥的安全存储
 */
class BitwardenRepository(private val context: Context) {
    internal val strings: StringResolver = AppLocaleStringResolver(context)

    data class AttachmentCipherSnapshot(
        val context: takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext,
        val attachments: List<CipherAttachmentApiData>
    )
    
    companion object {
        private const val TAG = "BitwardenRepository"
        private const val PREFS_NAME = "bitwarden_secure_prefs"
        private const val KEY_ACTIVE_VAULT_ID = "active_vault_id"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_SYNC_ON_WIFI_ONLY = "sync_on_wifi_only"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_NEVER_LOCK_BITWARDEN = "never_lock_bitwarden"
        private const val PBKDF2_DEFAULT_ITERATIONS = 600000
        private const val ARGON2_DEFAULT_ITERATIONS = 3
        private const val ARGON2_DEFAULT_MEMORY_MB = 64
        private const val ARGON2_DEFAULT_PARALLELISM = 4
        private const val CACHE_KEEP_SENTINEL_CIPHER_ID = "__MONICA_CACHE_KEEP_SENTINEL__"
        private const val FILE_UPLOAD_TYPE_DIRECT = 0
        private const val SEND_UPLOAD_REQUEST_HEADROOM_BYTES = 16L * 1024L
        private val vaultSyncMutexes = ConcurrentHashMap<Long, Mutex>()
        
        @Volatile
        private var instance: BitwardenRepository? = null
        
        fun getInstance(context: Context): BitwardenRepository {
            return instance ?: synchronized(this) {
                instance ?: BitwardenRepository(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * 将技术性错误消息转换为当前语言的用户提示
         */
        internal fun parseErrorMessage(rawError: String?, strings: StringResolver): String {
            if (rawError.isNullOrBlank()) return strings.get(R.string.bitwarden_message_unknown)
            
            return when {
                // 账号密码错误（也可能是新设备验证触发，Bitwarden 新版不区分这两种情况）
                rawError.contains("invalid_username_or_password", ignoreCase = true) ||
                rawError.contains("Username or password is incorrect", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_bad_login)
                
                // 验证码错误
                rawError.contains("Invalid New Device OTP", ignoreCase = true) ||
                rawError.contains("invalid new device otp", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_code_expired)
                
                rawError.contains("Two-step token is invalid", ignoreCase = true) ||
                rawError.contains("invalid two-step", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_two_step_invalid)
                
                // 需要新设备验证
                rawError.contains("New device verification required", ignoreCase = true) ||
                rawError.contains("new device verification", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_new_device)
                
                // Captcha 验证
                rawError.contains("captcha required", ignoreCase = true) &&
                rawError.contains("sitekey", ignoreCase = true).not() ->
                    strings.get(R.string.bitwarden_message_challenge_missing)

                rawError.contains("captcha", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_captcha)

                rawError.contains("Bitwarden Argon2id KDF memory is too high", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_kdf_memory)
                
                // 账户锁定
                rawError.contains("locked", ignoreCase = true) ||
                rawError.contains("too many attempts", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_login_locked)
                
                // 服务器错误
                rawError.contains("500") || rawError.contains("502") || 
                rawError.contains("503") || rawError.contains("504") ->
                    strings.get(R.string.bitwarden_message_server_unavailable)
                
                // 其他 400 错误
                rawError.contains("400") && rawError.contains("invalid_grant") ->
                    strings.get(R.string.bitwarden_message_auth_failed)

                // 高内存 Argon2id KDF 在当前设备/ABI/native 库不可用时的明确提示
                rawError.contains("Bitwarden Argon2id KDF requires", ignoreCase = true) ||
                rawError.contains("ARGON2_MEMORY_ALLOCATION_ERROR", ignoreCase = true) ||
                rawError.contains("ARGON2JNI_MALLOC_FAILED", ignoreCase = true) ->
                    strings.get(R.string.bitwarden_message_kdf_memory)
                
                // Remote bodies and transport exception text may echo credentials or signed URLs.
                else -> strings.get(R.string.bitwarden_account_operation_failed)
            }
        }

    }
    
    // Database DAOs
    private val database = PasswordDatabase.getDatabase(context)
    private val vaultDao = database.bitwardenVaultDao()
    private val folderDao = database.bitwardenFolderDao()
    private val sendDao = BitwardenSendCache(context)
    private val conflictDao = database.bitwardenConflictBackupDao()
    private val pendingOpDao = database.bitwardenPendingOperationDao()
    private val rawEntryRecordDao = database.bitwardenSyncRawEntryRecordDao()
    private val passwordEntryDao = database.passwordEntryDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val categoryDao = database.categoryDao()
    
    // Services
    private val transportSecurityManager by lazy { takagi.ru.monica.security.SecurityManager(context) }
    private val transportSecrets by lazy {
        BitwardenTransportSecretStore(
            read = { transportSecurityManager.getProtectedString(it) },
            write = { name, value -> transportSecurityManager.putProtectedString(name, value) },
            remove = { transportSecurityManager.removeProtectedString(it) },
        )
    }
    private val transportEnvironments by lazy { BitwardenVaultEnvironmentStore(transportSecrets) }
    private val accountMutationPrefs by lazy { context.applicationContext.getSharedPreferences("bitwarden_account_mutations", Context.MODE_PRIVATE) }
    private val accountMutations by lazy {
        BitwardenAccountMutationIntentStore(
            read = { accountMutationPrefs.getString(it, null) },
            write = { key, value -> accountMutationPrefs.edit().putString(key, value).commit() },
            remove = { accountMutationPrefs.edit().remove(it).commit() },
            seal = { key, plain -> transportSecurityManager.encryptData(key + "\n" + plain) },
            open = { key, sealed -> transportSecurityManager.decryptData(sealed).let { plain ->
                check(plain.startsWith(key + "\n")); plain.removePrefix(key + "\n")
            } },
        )
    }

    /** A durable marker blocks all account traffic even before its encrypted contents can be opened. */
    internal fun isAccountMutationBlocked(vaultId: Long): Boolean = runCatching {
        accountMutationPrefs.contains("bitwarden_account_mutation_v1_$vaultId")
    }.getOrDefault(true)
    private class RequestSessionRecord(
        val generation: Long, val appGeneration: Long, val key: SymmetricCryptoKey,
        val transportRevision: String, val secretRef: String?,
        val session: takagi.ru.monica.bitwarden.api.BitwardenAuthenticatedSession,
    )
    private val requestSessions = ConcurrentHashMap<Long, RequestSessionRecord>()
    private val apiManager = BitwardenApiManager(
        environmentForVault = { vault -> transportEnvironments.resolve(vault) },
        vaultRequestIsCurrent = { vault -> isTransportCurrent(vault) },
        attemptRequestIsCurrent = { environment ->
            takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                authenticationGenerations[environment.revision] == takagi.ru.monica.security.SessionManager.lockGeneration.value &&
                authenticationEditGuards[environment.revision]?.let(::isReauthenticationCurrent) != false
        },
        wrapVaultApi = { vault, api -> takagi.ru.monica.bitwarden.api.BitwardenAuthenticatedApi(api, requestSession(vault)) },
    )
    private val authService = BitwardenAuthService(context, apiManager)
    private val syncService = BitwardenSyncService(context, apiManager, sessionProfileValidator = ::validateBitwardenSessionProfile,
        sessionIsActive = { vaultId, key -> isVaultUnlocked(vaultId) && symmetricKeyCache[vaultId] === key },
        transportIsActive = ::isTransportCurrent,
        publication = { vault, key, action ->
            val token = requireNotNull(accessTokenCache[vault.id])
            publishAttachment(vault, attachmentGuard(vault, key, token), action)
        })
    private val historicalTotpRepairService = BitwardenHistoricalTotpRepairService(context, apiManager)

    /** Called on IO threads. A cached client never overrides current lock/configuration state. */
    private fun isTransportCurrent(vault: BitwardenVault): Boolean =
        !isAccountMutationBlocked(vault.id) && takagi.ru.monica.security.SessionManager.isUnlocked.value && isVaultUnlocked(vault.id) &&
            vaultDao.getTransportState(vault.id)?.matches(vault) == true

    internal suspend fun loadAccountEnvironmentDraft(vaultId: Long): Result<BitwardenEnvironmentDraft> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(withVaultTransportForBackup(vaultId) { vault, environment ->
                    check(isVaultUnlocked(vaultId) && !vault.isLocked) { "Bitwarden account is locked" }
                    environment.toDraft()
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Result.failure(IllegalStateException("Bitwarden configuration unavailable")) }
        }

    /** Only the caller's encrypted, explicitly requested configuration backup consumes this callback. */
    internal suspend fun <T> withVaultTransportForBackup(vaultId: Long,
        block: (BitwardenVault, BitwardenEnvironment) -> T): T = withContext(Dispatchers.IO) {
        val generation = takagi.ru.monica.security.SessionManager.lockGeneration.value
        syncMutexForVault(vaultId).withLock {
            val first = requireNotNull(vaultDao.getVaultById(vaultId))
            val scope = authenticationScope(first.serverUrl, first.email)
            val epoch = BitwardenAuthSessionGate.epoch(scope)
            fun current() {
                check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == generation &&
                    vaultDao.getVaultForTransportPublication(vaultId) == first) { "Bitwarden backup session changed" }
            }
            val environment = requireNotNull(BitwardenAuthSessionGate.publish(scope, epoch) {
                currentCoroutineContext().ensureActive()
                current()
                transportEnvironments.resolve(first)
            }) { "Bitwarden backup session changed" }
            // Encryption can be expensive. Revocation may proceed while this pure callback runs.
            val result = block(first, environment)
            requireNotNull(BitwardenAuthSessionGate.publish(scope, epoch) {
                currentCoroutineContext().ensureActive()
                current()
                Unit to result
            }) { "Bitwarden backup session changed" }.second
        }
    }

    private fun attachmentGuard(vault: BitwardenVault, key: SymmetricCryptoKey, token: String): () -> Unit {
        val generation = getVaultSessionGeneration(vault.id)
        return {
            check(isTransportCurrent(vault) && symmetricKeyCache[vault.id] === key &&
                isAccessTokenCurrent(vault.id, token) && getVaultSessionGeneration(vault.id) == generation) {
                "Bitwarden attachment session changed"
            }
        }
    }

    private val restoreConfigurationMutex = Mutex()
    private val selectionMonitor = Any()
    private var selectionEpoch = 0L

    internal class BitwardenRestoreSelection internal constructor(
        internal val owner: Any, internal val appGeneration: Long, internal val epoch: Long,
        internal val originalDefault: Long?, internal val originalActive: Long,
    ) {
        internal val published = linkedMapOf<Long, Triple<String, String?, String>>()
        internal var finished = false
        override fun toString() = "BitwardenRestoreSelection"
    }

    internal suspend fun beginVaultConfigurationRestoreSelection(expectedAppGeneration: Long): BitwardenRestoreSelection =
        withContext(Dispatchers.IO) {
            requireNotNull(takagi.ru.monica.security.SessionManager.publishAtGeneration(expectedAppGeneration) {
                synchronized(selectionMonitor) {
                    BitwardenRestoreSelection(this@BitwardenRepository, expectedAppGeneration, selectionEpoch,
                        vaultDao.getDefaultVaultIdForPublication(), securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1L) ?: -1L)
                }
            }) { "Bitwarden restore session changed" }
        }

    internal suspend fun finishVaultConfigurationRestoreSelection(selection: BitwardenRestoreSelection, preferredNewId: Long?) =
        withContext(Dispatchers.IO) {
            require(selection.owner === this@BitwardenRepository)
            val caller = currentCoroutineContext()
            takagi.ru.monica.security.SessionManager.publishAtGeneration(selection.appGeneration) {
                synchronized(selectionMonitor) {
                    check(!selection.finished)
                    require(preferredNewId == null || preferredNewId in selection.published)
                    selection.finished = true
                    if (selectionEpoch == selection.epoch) {
                        caller.ensureActive()
                        val chosenId = database.runInTransaction(java.util.concurrent.Callable<Long?> {
                            val valid = selection.published.mapNotNull { (id, stamp) ->
                                vaultDao.getVaultForTransportPublication(id)?.takeIf {
                                    Triple(it.accountKey, it.transportSecretRef, it.transportRevision) == stamp
                                }
                            }
                            val chosen = valid.firstOrNull { it.id == preferredNewId } ?: valid.firstOrNull()
                            if (chosen != null) {
                                if (selection.originalDefault == null && vaultDao.getDefaultVaultIdForPublication() == null) {
                                    vaultDao.publishTransport(chosen.copy(isDefault = true, updatedAt = System.currentTimeMillis()))
                                }
                            }
                            caller.ensureActive()
                            chosen?.id
                        })
                        // Preferences cannot participate in Room rollback. Publish the active choice
                        // only after a successful commit, while both selection/session monitors remain held.
                        if (chosenId != null && selection.originalActive <= 0 &&
                            (securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1L) ?: -1L) <= 0) {
                            setActiveVault(chosenId)
                        }
                    }
                }
            }
            Unit
        }

    /** Restore adds a missing account only; it can never retarget an existing account's credentials. */
    internal suspend fun restoreVaultConfigurationFromBackup(candidate: BitwardenVault,
        environment: BitwardenEnvironment?, expectedAppGeneration: Long,
        selection: BitwardenRestoreSelection? = null): Long? = withContext(Dispatchers.IO) {
        require(candidate.id == 0L && candidate.transportSecretRef == null && candidate.transportRevision.isEmpty())
        if (selection != null) synchronized(selectionMonitor) {
            require(selection.owner === this@BitwardenRepository && selection.appGeneration == expectedAppGeneration && !selection.finished)
        }
        val email = BitwardenVaultIdentity.canonicalizeEmail(candidate.email)
        val restored = if (environment != null) {
            requireNotNull(BitwardenEnvironmentResolver.resolve(environment.toDraft(), java.util.UUID.randomUUID().toString()).value)
        } else {
            require(candidate.environmentPreset == null && candidate.webVaultUrl == null && candidate.notificationsUrl == null)
            requireNotNull(BitwardenEnvironmentResolver.resolveLegacy(
                BitwardenLegacyEnvironmentSnapshot(candidate.serverUrl, candidate.apiUrl, candidate.identityUrl),
                java.util.UUID.randomUUID().toString()).value)
        }
        require(restored.base == BitwardenEnvironmentResolver.validateEndpoint(candidate.serverUrl,
            takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.BASE).value)
        require(restored.api.toString() == BitwardenEnvironmentResolver.validateEndpoint(candidate.apiUrl,
            takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.API).value?.toString())
        require(restored.identity.toString() == BitwardenEnvironmentResolver.validateEndpoint(candidate.identityUrl,
            takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.IDENTITY).value?.toString())
        val accountKey = BitwardenVaultIdentity.buildAccountKey(restored.base.toString(), candidate.userId, email)
        val scope = authenticationScope(restored.base.toString(), email)
        fun current() {
            check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                takagi.ru.monica.security.SessionManager.lockGeneration.value == expectedAppGeneration) {
                "Bitwarden restore session changed"
            }
        }
        var existingId: Long? = null
        val restoredId = restoreConfigurationMutex.withLock {
            BitwardenAuthSessionGate.inspect(scope) {
                currentCoroutineContext().ensureActive()
                current()
                val existing = vaultDao.getVaultByAccountKey(accountKey)
                    ?: vaultDao.getVaultByServerAndCanonicalEmail(restored.base.toString(), email)
                if (existing != null) {
                    existingId = existing.id
                    return@inspect null
                }
                val reservation = BitwardenVault(email = candidate.email, canonicalEmail = email,
                    userId = candidate.userId, accountKey = accountKey, serverUrl = restored.base.toString(),
                    apiUrl = restored.api.toString(), identityUrl = restored.identity.toString(), isLocked = true, isConnected = false)
                val reserved = reservation.copy(id = vaultDao.insert(reservation))
                currentCoroutineContext().ensureActive()
                current()
                val binding = environment?.let { transportSecrets.stage(reserved.id, restored) }
                val saved = candidate.copy(id = reserved.id, canonicalEmail = email, accountKey = accountKey,
                    serverUrl = restored.base.toString(), apiUrl = restored.api.toString(), identityUrl = restored.identity.toString(),
                    environmentPreset = restored.preset.name.takeIf { binding != null },
                    webVaultUrl = restored.web.toString().takeIf { binding != null && restored.webExplicit },
                    notificationsUrl = restored.notifications.toString().takeIf { binding != null },
                    transportRevision = binding?.revision.orEmpty(), transportSecretRef = binding?.reference,
                    tlsCertificateAlias = null, tlsCaCertificatePem = null, tlsMtlsEnabled = false,
                    tlsClientCertPkcs12Base64 = null, tlsEncryptedClientCertPassword = null,
                    isLocked = true, isDefault = selection == null && vaultDao.getDefaultVault() == null, updatedAt = System.currentTimeMillis())
                val caller = currentCoroutineContext()
                check(takagi.ru.monica.security.SessionManager.publishAtGeneration(expectedAppGeneration) {
                    caller.ensureActive()
                    synchronized(selectionMonitor) {
                        // A finish can run while transport material is being staged. Reject it before
                        // committing credentials, and keep the batch check/SQL/registration inseparable.
                        if (selection != null) check(!selection.finished)
                        database.runInTransaction {
                            check(vaultDao.getVaultForTransportPublication(reserved.id) == reserved)
                            vaultDao.publishTransport(saved)
                            caller.ensureActive()
                        }
                        if (selection != null) {
                            selection.published[saved.id] = Triple(saved.accountKey, saved.transportSecretRef, saved.transportRevision)
                        }
                    }
                    true
                } == true) { "Bitwarden restore session changed" }
                if (selection == null && (securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1) ?: -1) <= 0) setActiveVault(saved.id)
                saved.id
            }
        }
        // Revoke outside inspect: forceLock owns the same account gate and finishes even if UI cancellation follows.
        existingId?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { forceLock(id) } }
        restoredId
    }

    private suspend fun publishAttachment(vault: BitwardenVault, current: () -> Unit,
        action: suspend () -> Unit) {
        BitwardenAuthSessionGate.inspect(authenticationScope(vault.serverUrl, vault.email)) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                current()
                action()
                currentCoroutineContext().ensureActive()
                current()
            }
        }
    }

    private val authenticationEnvironments = ConcurrentHashMap<String, BitwardenEnvironment>()
    private val authenticationGenerations = ConcurrentHashMap<String, Long>()
    private val authenticationEditGuards = ConcurrentHashMap<String, ReauthenticationContext>()

    data class ReauthenticationContext(val vaultId: Long, val vaultGeneration: Long, val appGeneration: Long)

    fun isReauthenticationCurrent(context: ReauthenticationContext): Boolean =
        takagi.ru.monica.security.SessionManager.isUnlocked.value &&
            takagi.ru.monica.security.SessionManager.lockGeneration.value == context.appGeneration &&
            getVaultSessionGeneration(context.vaultId) == context.vaultGeneration

    internal fun retireAuthenticationEnvironment(environment: BitwardenEnvironment) {
        authenticationGenerations.remove(environment.revision)
        authenticationEditGuards.remove(environment.revision)
        authenticationEnvironments.entries.removeAll { it.value.revision == environment.revision }
        authService.retireEnvironment(environment)
    }
    
    // 加密的 SharedPreferences
    private val securePrefs by lazy {
        try {
            takagi.ru.monica.security.RecoverableBitwardenSettings.open(context)
        } catch (_: Exception) {
            Log.w(TAG, "Bitwarden settings unavailable; preserving originals and disabling automatic sync")
            null
        }
    }

    
    // 内存中的密钥缓存（不持久化）
    /** Fresh, complete personal-vault JSON snapshot; does not apply server data or mutate ciphers. */
    internal suspend fun exportPasswordManagerJson(vaultId: Long): takagi.ru.monica.bitwarden.export.BitwardenJsonExport =
        withContext(Dispatchers.IO) {
            syncMutexForVault(vaultId).withLock {
                val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
                val generation = getVaultSessionGeneration(vaultId)
                val vault = requireNotNull(vaultDao.getVaultById(vaultId))
                val fingerprint = takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(vault)
                val key = requireNotNull(symmetricKeyCache[vaultId])
                var token = requireNotNull(accessTokenCache[vaultId])
                fun current() {
                    check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                        takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                        isVaultUnlocked(vaultId) && getVaultSessionGeneration(vaultId) == generation &&
                        symmetricKeyCache[vaultId] === key && isAccessTokenCurrent(vaultId, token)) { "Export account session changed" }
                }
                suspend fun checkAccount() {
                    current()
                    val account = requireNotNull(vaultDao.getVaultById(vaultId))
                    check(account.isConnected && !account.isLocked && takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(account) == fingerprint) { "Export account identity changed" }
                    val pending = pendingOpDao.observeQueueRecords(vaultId).first().filter { it.kind != "SEND" }
                    if (pending.isNotEmpty()) throw takagi.ru.monica.bitwarden.export.BitwardenJsonRejected(pending.map {
                        takagi.ru.monica.bitwarden.export.BitwardenJsonIssue(null, it.title?.take(200), "queue.${it.kind}",
                            takagi.ru.monica.bitwarden.export.BitwardenJsonIssue.Reason.PENDING_SYNC)
                    })
                }
                checkAccount()
                if ((vault.accessTokenExpiresAt ?: 0) <= System.currentTimeMillis() + 60_000) {
                    val oldToken = token
                    token = requireNotNull(refreshToken(vault, publishIfCurrent = {
                        takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                            takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                            getVaultSessionGeneration(vaultId) == generation && symmetricKeyCache[vaultId] === key &&
                            isAccessTokenCurrent(vaultId, oldToken) &&
                            vaultDao.getVaultById(vaultId)?.let { takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(it) == fingerprint } == true
                    })) { "Cannot refresh export account" }
                    check(isAccessTokenCurrent(vaultId, token))
                }
                checkAccount()
                val response = apiManager.getVaultApi(vault).syncSource("Bearer $token")
                check(response.isSuccessful) { "Cannot read complete export source (${response.code()})" }
                val document = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.read(requireNotNull(response.body()), sync = true)
                val sync = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.decodeSync(document)
                currentCoroutineContext().ensureActive()
                check(validateBitwardenSessionProfile(vaultId, token, sync.profile) {
                    runCatching { current(); true }.getOrDefault(false)
                }) { "Export account security state changed" }
                checkAccount()
                val result = takagi.ru.monica.bitwarden.export.BitwardenJsonSourceExporter.fromSync(sync, key, ::current)
                checkAccount()
                result
            }
        }

    /** Read complete native ciphers for analysis; the callback never receives an API mutation handle. */
    internal suspend fun <T> withDedupSource(
        vaultId: Long,
        checkActive: () -> Unit,
        block: (BitwardenVault, takagi.ru.monica.bitwarden.api.SyncResponse, SymmetricCryptoKey) -> T,
    ): T = withContext(Dispatchers.IO) {
        syncMutexForVault(vaultId).withLock {
            val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            val generation = getVaultSessionGeneration(vaultId)
            val vault = requireNotNull(vaultDao.getVaultById(vaultId))
            val fingerprint = takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(vault)
            val key = requireNotNull(symmetricKeyCache[vaultId])
            var token = requireNotNull(accessTokenCache[vaultId])
            fun current() {
                checkActive()
                check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                    isVaultUnlocked(vaultId) && getVaultSessionGeneration(vaultId) == generation &&
                    symmetricKeyCache[vaultId] === key && isAccessTokenCurrent(vaultId, token))
            }
            suspend fun account() {
                current()
                val present = requireNotNull(vaultDao.getVaultById(vaultId))
                check(present.isConnected && !present.isLocked &&
                    takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(present) == fingerprint)
                check(pendingOpDao.observeQueueRecords(vaultId).first().none { it.kind != "SEND" })
            }
            account()
            if ((vault.accessTokenExpiresAt ?: 0) <= System.currentTimeMillis() + 60_000) {
                val oldToken = token
                token = requireNotNull(refreshToken(vault, publishIfCurrent = {
                    runCatching { checkActive(); true }.getOrDefault(false) &&
                        takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                        takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                        getVaultSessionGeneration(vaultId) == generation && symmetricKeyCache[vaultId] === key &&
                        isAccessTokenCurrent(vaultId, oldToken) &&
                        vaultDao.getVaultById(vaultId)?.let {
                            takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(it) == fingerprint
                        } == true
                }))
            }
            account()
            val response = apiManager.getVaultApi(vault).syncSource("Bearer $token")
            check(response.isSuccessful)
            val document = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.read(requireNotNull(response.body()), sync = true)
            val native = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.decodeSync(document)
            currentCoroutineContext().ensureActive()
            check(validateBitwardenSessionProfile(vaultId, token, native.profile) {
                runCatching { current(); true }.getOrDefault(false)
            })
            account()
            block(vault, native, key).also { account() }
        }
    }

    private val syncIssues = takagi.ru.monica.bitwarden.service.BitwardenSyncIssues()
    val syncIssueReports = syncIssues.reports
    private val vaultSessionGenerations = ConcurrentHashMap<Long, kotlinx.coroutines.flow.MutableStateFlow<Long>>()
    private val vaultAppGenerations = ConcurrentHashMap<Long, Long>()

    fun getVaultSessionGenerationFlow(vaultId: Long): kotlinx.coroutines.flow.StateFlow<Long> =
        vaultSessionGenerations.getOrPut(vaultId) { kotlinx.coroutines.flow.MutableStateFlow(0L) }

    fun getVaultSessionGeneration(vaultId: Long): Long = getVaultSessionGenerationFlow(vaultId).value

    private fun advanceVaultSession(vaultId: Long) {
        requestSessions.remove(vaultId)?.session?.retire()
        apiManager.retireVault(vaultId)
        synchronized(vaultSessionGenerations) {
            val state = vaultSessionGenerations.getOrPut(vaultId) { kotlinx.coroutines.flow.MutableStateFlow(0L) }
            state.value = state.value + 1L
            syncIssues.clear(vaultId)
        }
    }

    // All replacements and removals, including test injection, revoke in-flight readers.
    private val symmetricKeyCache = object : ConcurrentHashMap<Long, SymmetricCryptoKey>() {
        override fun put(key: Long, value: SymmetricCryptoKey): SymmetricCryptoKey? {
            advanceVaultSession(key)
            vaultAppGenerations[key] = takagi.ru.monica.security.SessionManager.lockGeneration.value
            return super.put(key, value).also { advanceVaultSession(key) }
        }
        override fun remove(key: Long): SymmetricCryptoKey? {
            advanceVaultSession(key)
            vaultAppGenerations.remove(key)
            return super.remove(key).also { advanceVaultSession(key) }
        }
        override fun clear() {
            val ids = keys.toList()
            vaultAppGenerations.clear()
            ids.forEach(::advanceVaultSession)
            super.clear()
            ids.forEach(::advanceVaultSession)
            syncIssues.clearAll()
        }
    }
    private val accessTokenCache = ConcurrentHashMap<Long, String>()

    private val accountIdentityStore by lazy {
        takagi.ru.monica.bitwarden.service.BitwardenAccountIdentityStore(
            read = { name -> securePrefs?.getString(name, null) },
            write = { name, value -> securePrefs?.edit()?.putString(name, value)?.commit() == true },
            remove = { name -> securePrefs?.edit()?.remove(name)?.commit(); Unit },
        )
    }

    private val rememberedTwoFactorStore by lazy {
        BitwardenRememberedTwoFactorStore(
            readSealed = { key -> runCatching { securePrefs?.getString(key, null) }.getOrNull() },
            writeSealed = { key, value -> runCatching { securePrefs?.edit()?.putString(key, value)?.commit() == true }.getOrDefault(false) },
            removeSealed = { key -> runCatching { securePrefs?.edit()?.remove(key)?.commit() == true }.getOrDefault(false) },
            listSealedKeys = { runCatching { securePrefs?.all?.keys.orEmpty().toSet() }.getOrDefault(emptySet()) },
        )
    }

    private fun authenticationScope(serverUrl: String, email: String): String =
        BitwardenRememberedTwoFactorStore.scope(
            BitwardenVaultIdentity.normalizeServerUrl(serverUrl),
            BitwardenVaultIdentity.canonicalizeEmail(email),
        )

    private fun rememberedEnvironmentScope(scope: String, environment: BitwardenEnvironment): String =
        "$scope:transport-v2:${environment.fingerprint}"

    /** Called only with a successful profile response, before any writes or sync projection. */
    private suspend fun validateBitwardenSessionProfile(
        vaultId: Long,
        expectedAccessToken: String,
        profile: ProfileResponse,
        requestIsCurrent: () -> Boolean,
    ): Boolean {
        if (!requestIsCurrent()) return false
        val vault = vaultDao.getVaultById(vaultId) ?: return false
        val scope = authenticationScope(vault.serverUrl, vault.email)
        val identityGeneration = getVaultSessionGeneration(vaultId)
        val identityAppGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        val decision = BitwardenAuthSessionGate.inspect(scope) {
            val current = vaultDao.getVaultById(vaultId) ?: return@inspect null
            if (!requestIsCurrent() || !isAccessTokenCurrent(vaultId, expectedAccessToken) || !isVaultUnlocked(vaultId)) return@inspect null
            if (!current.userId.isNullOrBlank() && current.userId != profile.id) {
                return@inspect BitwardenStampDecision.CHANGED
            }
            val comparison = BitwardenSecurityStampPolicy.compare(current.revisionDate, profile.securityStamp)
            if (comparison == BitwardenStampDecision.FIRST_OBSERVATION) {
                vaultDao.update(current.copy(revisionDate = profile.securityStamp))
            }
            if (comparison == BitwardenStampDecision.MATCH || comparison == BitwardenStampDecision.FIRST_OBSERVATION) {
                val key = symmetricKeyCache[vaultId]
                if (key != null && BitwardenVaultIdentity.canonicalizeEmail(current.email) ==
                    BitwardenVaultIdentity.canonicalizeEmail(profile.email)) {
                    accountIdentityStore.capture(current, profile, key) {
                        requestIsCurrent() && identityGeneration == getVaultSessionGeneration(vaultId) && symmetricKeyCache[vaultId] === key &&
                            isAccessTokenCurrent(vaultId, expectedAccessToken) &&
                            takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                            identityAppGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value
                    }
                }
            }
            comparison
        }
        if (decision == BitwardenStampDecision.CHANGED) {
            invalidateBitwardenSession(vaultId, expectedAccessToken, requestIsCurrent)
        }
        return requestIsCurrent() && (decision == BitwardenStampDecision.FIRST_OBSERVATION || decision == BitwardenStampDecision.MATCH)
    }

    /** Stamp revocation preserves records/queue and bypasses the optional never-lock preference. */
    private suspend fun invalidateBitwardenSession(vaultId: Long, expectedAccessToken: String, requestIsCurrent: () -> Boolean): Boolean {
        if (isAccountMutationBlocked(vaultId)) return false
        val vault = vaultDao.getVaultById(vaultId) ?: return false
        val scope = authenticationScope(vault.serverUrl, vault.email)
        return BitwardenAuthSessionGate.revokeIf(scope,
            predicate = { requestIsCurrent() && isAccessTokenCurrent(vaultId, expectedAccessToken) && isVaultUnlocked(vaultId) },
            action = {
                accountIdentityStore.clear(vaultId)
                symmetricKeyCache.remove(vaultId)?.clear()
                accessTokenCache.remove(vaultId)
                rememberedTwoFactorStore.clearAccount(scope)
                vaultDao.getVaultById(vaultId)?.let { current ->
                    vaultDao.update(current.copy(
                        encryptedAccessToken = null, encryptedRefreshToken = null,
                        accessTokenExpiresAt = null, encryptedMasterKey = null,
                        encryptedEncKey = null, encryptedMacKey = null,
                        isLocked = true, isConnected = false, updatedAt = System.currentTimeMillis(),
                    ))
                }
            },
        )
    }

    /** Observe actual preferences across all ViewModel instances, with conservative failure defaults. */
    internal fun observeNotificationPreferences(): Flow<Pair<Boolean, Boolean>> = callbackFlow {
        val preferences = securePrefs
        if (preferences == null) { trySend(false to true); close(); return@callbackFlow }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_AUTO_SYNC_ENABLED || key == KEY_SYNC_ON_WIFI_ONLY) {
                trySend(isAutoSyncEnabled to isSyncOnWifiOnly)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(isAutoSyncEnabled to isSyncOnWifiOnly)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Short-lived, account-bound capability for foreground notifications; never exposes vault keys. */
    internal suspend fun getNotificationSession(
        vaultId: Long,
        forceRefresh: Boolean = false,
    ): takagi.ru.monica.bitwarden.sync.BitwardenNotificationSession? = withContext(Dispatchers.IO) {
        syncMutexForVault(vaultId).withLock {
            val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            val generation = getVaultSessionGeneration(vaultId)
            val vault = vaultDao.getVaultById(vaultId) ?: return@withLock null
            val fingerprint = takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(vault)
            val userId = vault.userId?.takeIf { it.isNotBlank() } ?: return@withLock null
            val url = BitwardenTransportPolicy.notificationHubUrl(apiManager.environment(vault))
            var token = accessTokenCache[vaultId] ?: return@withLock null
            suspend fun current(): Boolean =
                takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                    generation == getVaultSessionGeneration(vaultId) && isVaultUnlocked(vaultId) &&
                    isAutoSyncEnabled && vaultDao.getVaultById(vaultId)?.let {
                        it.syncEnabled && takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(it) == fingerprint
                    } == true
            if (!current()) return@withLock null
            if (forceRefresh || (vault.accessTokenExpiresAt ?: 0) <= System.currentTimeMillis() + 60_000) {
                val previous = token
                token = refreshToken(vault, publishIfCurrent = { current() && isAccessTokenCurrent(vaultId, previous) },
                    rejectedAccessToken = if (forceRefresh) previous else null)
                    ?: return@withLock null
                currentCoroutineContext().ensureActive()
                if (!current() || accessTokenCache[vaultId] != token) return@withLock null
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            val expiresAt = vaultDao.getVaultById(vaultId)?.accessTokenExpiresAt ?: return@withLock null
            takagi.ru.monica.bitwarden.sync.BitwardenNotificationSession(
                vaultId, generation, appGeneration, fingerprint, userId,
                context.getSharedPreferences("bitwarden_device", Context.MODE_PRIVATE).getString("device_id", null),
                url, token, expiresAt, apiManager.getOkHttpClient(vault),
            )
        }
    }

    internal suspend fun isNotificationSessionCurrent(
        session: takagi.ru.monica.bitwarden.sync.BitwardenNotificationSession,
    ): Boolean = takagi.ru.monica.security.SessionManager.isUnlocked.value &&
        takagi.ru.monica.security.SessionManager.lockGeneration.value == session.appGeneration &&
        getVaultSessionGeneration(session.vaultId) == session.generation && isVaultUnlocked(session.vaultId) &&
        !isAccountMutationBlocked(session.vaultId) &&
        isAutoSyncEnabled && accessTokenCache[session.vaultId] == session.accessToken &&
        vaultDao.getVaultById(session.vaultId)?.let {
            it.syncEnabled && takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(it) == session.accountFingerprint
        } == true

    /** Account-bound native device list; no encrypted key material is exposed to UI. */
    suspend fun getAccountDevices(vaultId: Long): Result<List<BitwardenAccountDevice>> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))
            val generation = getVaultSessionGeneration(vaultId)
            val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            val fingerprint = vault.accountKey + takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(vault)
            suspend fun current(): Boolean = takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                generation == getVaultSessionGeneration(vaultId) && isVaultUnlocked(vaultId) &&
                vaultDao.getVaultById(vaultId)?.let {
                    !it.isLocked && it.accountKey + takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(it) == fingerprint
                } == true
            if (!current()) return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
            var token = accessTokenCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_reauthenticate)))
            if ((vault.accessTokenExpiresAt ?: 0) <= System.currentTimeMillis() + 60_000) {
                val previous = token
                val refreshed = refreshToken(vault, publishIfCurrent = { current() && isAccessTokenCurrent(vaultId, previous) })
                    ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                if (!current() || !isAccessTokenCurrent(vaultId, refreshed)) {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_reauthenticate)))
                }
                token = refreshed
            }
            val api = apiManager.getVaultApi(vault)
            val profileResponse = api.profile("Bearer $token")
            val profile = profileResponse.body()
            if (!profileResponse.isSuccessful || profile == null) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_account_request_failed, profileResponse.code())))
            }
            if (!current() || !validateBitwardenSessionProfile(vaultId, token, profile) {
                generation == getVaultSessionGeneration(vaultId) &&
                    appGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value
            }) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_reauthenticate)))
            }
            val response = api.getAccountDevices("Bearer $token")
            if (!response.isSuccessful) return@withContext Result.failure(IllegalStateException(
                strings.get(R.string.bitwarden_account_request_failed, response.code())))
            currentCoroutineContext().ensureActive()
            if (!isAccessTokenCurrent(vaultId, token) || !current()) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_reauthenticate)))
            }
            val devices = response.body()?.data ?: return@withContext Result.failure(IllegalStateException(
                strings.get(R.string.bitwarden_account_operation_failed)))
            Result.success(devices.filter { it.id.isNotBlank() }.distinctBy { it.id })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    
    /** Derive locally from the last authenticated profile, without a network request or key generation. */
    suspend fun getAccountFingerprint(vaultId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId) ?: error("Account unavailable")
            val generation = getVaultSessionGeneration(vaultId)
            val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            val key = symmetricKeyCache[vaultId] ?: error("Account locked")
            suspend fun current(): Boolean {
                val currentVault = vaultDao.getVaultById(vaultId)
                return takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
                    getVaultSessionGeneration(vaultId) == generation && symmetricKeyCache[vaultId] === key &&
                    isVaultUnlocked(vaultId) && currentVault?.let {
                    !it.isLocked && it.accountKey == vault.accountKey && it.userId == vault.userId &&
                        it.serverUrl == vault.serverUrl && it.email == vault.email && it.revisionDate == vault.revisionDate
                } == true
            }
            check(current())
            val publicKey = accountIdentityStore.publicKey(vault, key) ?: error("Identity unavailable")
            val phrase = try {
                val words = context.resources.openRawResource(R.raw.bitwarden_fingerprint_words)
                    .bufferedReader().use { it.readLines() }
                takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint.phrase(
                    requireNotNull(vault.userId), publicKey, words)
            } finally { publicKey.fill(0) }
            currentCoroutineContext().ensureActive()
            check(current())
            Result.success(phrase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException(strings.get(R.string.bitwarden_account_fingerprint_unavailable)))
        }
    }

    // ==================== Vault 管理 ====================
    
    /**
     * 获取所有 Vault
     */
    private fun accountKdf(vault: BitwardenVault) = BitwardenAccountMutationKdf(
        vault.kdfType, vault.kdfIterations, vault.kdfMemory, vault.kdfParallelism)

    private fun accountMutationBinding(vault: BitwardenVault, environment: BitwardenEnvironment) =
        BitwardenAccountMutationBinding(vault.id, requireNotNull(vault.userId),
            BitwardenVaultIdentity.resolveCanonicalEmail(vault), environment.fingerprint,
            vault.transportRevision, vault.transportSecretRef, getVaultSessionGeneration(vault.id),
            takagi.ru.monica.security.SessionManager.lockGeneration.value, vault.revisionDate)

    private suspend fun readAccountMutationSnapshot(vaultId: Long): BitwardenAccountMutationSnapshot {
        val original = requireNotNull(vaultDao.getVaultById(vaultId))
        check(isTransportCurrent(original))
        val key = requireNotNull(symmetricKeyCache[vaultId])
        val generation = getVaultSessionGeneration(vaultId)
        val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        fun current() = isTransportCurrent(original) && symmetricKeyCache[vaultId] === key &&
            getVaultSessionGeneration(vaultId) == generation &&
            takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration
        val api = apiManager.getVaultApi(original)
        val token = requireNotNull(refreshToken(original))
        val response = api.profileDocument("Bearer $token")
        response.errorBody()?.close()
        check(response.isSuccessful && current())
        val document = requireNotNull(response.body())
        val profile = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<ProfileResponse>(document.toString())
        check(validateBitwardenSessionProfile(vaultId, token, profile, ::current))
        val vault = requireNotNull(vaultDao.getVaultById(vaultId))
        check(!profile.id.isBlank() && (vault.userId.isNullOrBlank() || vault.userId == profile.id))
        val prelogin = apiManager.getIdentityApi(vault).preLoginDocument(
            takagi.ru.monica.bitwarden.api.PreLoginRequest(BitwardenVaultIdentity.resolveCanonicalEmail(vault)))
        prelogin.errorBody()?.close()
        check(prelogin.isSuccessful && current())
        val metadata = requireNotNull(prelogin.body())
        fun number(name: String) = BitwardenAccountMutationMetadata.field(metadata, name)?.jsonPrimitive?.intOrNull
        check(number("kdf") == vault.kdfType && number("kdfIterations") == vault.kdfIterations &&
            number("kdfMemory") == vault.kdfMemory && number("kdfParallelism") == vault.kdfParallelism)
        val binding = accountMutationBinding(vault.copy(userId = profile.id), transportEnvironments.resolve(vault))
            .copy(accountGeneration = generation, appGeneration = appGeneration)
        check(current())
        return BitwardenAccountMutationMetadata.snapshot(binding, accountKdf(vault), document)
    }

    internal suspend fun loadAccountManagement(vaultId: Long): Result<BitwardenAccountManagementInfo> = withContext(Dispatchers.IO) {
        try {
            check(takagi.ru.monica.security.SessionManager.isUnlocked.value)
            val vault = requireNotNull(vaultDao.getVaultById(vaultId))
            val stored = accountMutations.restore(vaultId)
            val state = when (stored) {
                BitwardenAccountMutationStored.Absent -> BitwardenAccountManagementState.READY
                BitwardenAccountMutationStored.Unreadable -> BitwardenAccountManagementState.UNREADABLE
                is BitwardenAccountMutationStored.Present -> if (stored.record.kind == BitwardenAccountMutationKind.DELETE &&
                    stored.record.phase == BitwardenAccountMutationPhase.CONFIRMED) BitwardenAccountManagementState.DELETED_LOCAL_COPY
                    else BitwardenAccountManagementState.REAUTHENTICATE
            }
            val snapshot = if (state == BitwardenAccountManagementState.READY) readAccountMutationSnapshot(vaultId) else null
            Result.success(BitwardenAccountManagementInfo(vaultId, vault.email, vault.serverUrl, snapshot?.kdf ?: accountKdf(vault),
                snapshot?.hintPresent == true, snapshot?.hint, state))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure(IllegalStateException(strings.get(R.string.bitwarden_account_operation_failed))) }
    }

    /** Reuse only the stored account environment when reconciling a change; no replacement destination. */
    internal suspend fun loadAccountRecoveryEnvironment(vaultId: Long): BitwardenEnvironmentDraft = withContext(Dispatchers.IO) {
        check(takagi.ru.monica.security.SessionManager.isUnlocked.value)
        transportEnvironments.resolve(requireNotNull(vaultDao.getVaultById(vaultId))).toDraft()
    }

    internal suspend fun mutateAccount(vaultId: Long, currentPassword: String,
        action: BitwardenAccountMutationAction,
        expectedVaultGeneration: Long = getVaultSessionGeneration(vaultId),
        expectedAppGeneration: Long = takagi.ru.monica.security.SessionManager.lockGeneration.value,
    ): Result<BitwardenAccountManagementResult> = withContext(Dispatchers.IO) {
        try {
            Result.success(syncMutexForVault(vaultId).withLock {
                check(!isAccountMutationBlocked(vaultId))
                check(getVaultSessionGeneration(vaultId) == expectedVaultGeneration &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == expectedAppGeneration)
                val snapshot = readAccountMutationSnapshot(vaultId)
                check(snapshot.binding.accountGeneration == expectedVaultGeneration && snapshot.binding.appGeneration == expectedAppGeneration)
                val vault = requireNotNull(vaultDao.getVaultById(vaultId))
                val key = requireNotNull(symmetricKeyCache[vaultId])
                val protocol = BitwardenAccountMutationProtocol()
                val prepared = protocol.prepare(snapshot, action, currentPassword, key)
                val binding = prepared.record.binding
                val environment = transportEnvironments.resolve(vault)
                val token = requireNotNull(accessTokenCache[vaultId])
                val active = java.util.concurrent.atomic.AtomicBoolean(true)
                fun current(): Boolean = active.get() && takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == binding.appGeneration &&
                    getVaultSessionGeneration(vaultId) == binding.accountGeneration && symmetricKeyCache[vaultId] === key &&
                    vaultDao.getTransportState(vaultId)?.matches(vault) == true
                val client = BitwardenApiFactory.createAccountClient(environment, requestIsCurrent = ::current)
                    .newBuilder().retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE).build()
                val api = BitwardenApiFactory.createVaultApi(environment.api.toString(), client)
                var keepSession = false
                try {
                    val coordinator = BitwardenAccountMutationCoordinator(accountMutations,
                        requireCurrent = { check(it == binding && current()) },
                        freeze = {
                            check(current())
                            requestSessions.remove(vaultId)?.session?.retire()
                            apiManager.retireVault(vaultId)
                        },
                        execute = { request ->
                            val response = when (prepared.record.kind) {
                                BitwardenAccountMutationKind.HINT_PROFILE -> api.updateAccountProfile("Bearer $token", request.body)
                                BitwardenAccountMutationKind.KDF -> api.changeAccountKdf("Bearer $token", request.body)
                                BitwardenAccountMutationKind.DELETE -> api.deleteAccount("Bearer $token", request.body)
                                else -> api.changeAccountPassword("Bearer $token", request.body)
                            }
                            val status = response.code()
                            response.body()?.close(); response.errorBody()?.close()
                            val readback = if (status in 200..299 && prepared.record.kind == BitwardenAccountMutationKind.HINT_PROFILE) {
                                val verified = api.profileDocument("Bearer $token")
                                verified.errorBody()?.close()
                                verified.body().takeIf { verified.isSuccessful }
                            } else null
                            // No generic error (including 4xx) proves that a server mutation did not run.
                            BitwardenAccountMutationHttpResult(status, readback)
                        })
                    when (val outcome = coordinator.send(prepared)) {
                        is BitwardenAccountMutationOutcome.Confirmed -> when (outcome.record.kind) {
                            BitwardenAccountMutationKind.HINT_PROFILE -> {
                                keepSession = accountMutations.acknowledgeReconciled(outcome.record)
                                if (keepSession) BitwardenAccountManagementResult.HINT_SAVED else BitwardenAccountManagementResult.UNCERTAIN
                            }
                            BitwardenAccountMutationKind.DELETE -> BitwardenAccountManagementResult.DELETED_LOCAL_COPY
                            else -> BitwardenAccountManagementResult.REAUTHENTICATE
                        }
                        BitwardenAccountMutationOutcome.NotSent -> BitwardenAccountManagementResult.NOT_SENT
                        else -> BitwardenAccountManagementResult.UNCERTAIN
                    }
                } finally {
                    active.set(false); prepared.discard()
                    client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                    if (!keepSession && isAccountMutationBlocked(vaultId)) withContext(kotlinx.coroutines.NonCancellable) {
                        // Never call logout/invalidate: original records, queues, files and protected keys survive.
                        BitwardenAuthSessionGate.inspect(authenticationScope(vault.serverUrl, vault.email)) {
                            val latest = vaultDao.getVaultById(vaultId)
                            if (latest != null && latest.transportRevision == binding.transportRevision &&
                                latest.transportSecretRef == binding.transportSecretRef) {
                                symmetricKeyCache.remove(vaultId)?.clear(); accessTokenCache.remove(vaultId)
                                rememberedTwoFactorStore.clearAccount(authenticationScope(vault.serverUrl, vault.email))
                                vaultDao.update(latest.copy(encryptedAccessToken = null, encryptedRefreshToken = null,
                                    accessTokenExpiresAt = null, isLocked = true, isConnected = false,
                                    updatedAt = System.currentTimeMillis()))
                            }
                        }
                    }
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure(IllegalStateException(strings.get(R.string.bitwarden_account_operation_failed))) }
    }

    private fun verifyPreservedAccountKey(original: BitwardenVault, received: SymmetricCryptoKey) {
        if (original.encryptedEncKey == null && original.encryptedMacKey == null) return
        val enc = Base64.decode(decryptFromStorage(requireNotNull(original.encryptedEncKey)), Base64.NO_WRAP)
        val mac = Base64.decode(decryptFromStorage(requireNotNull(original.encryptedMacKey)), Base64.NO_WRAP)
        try {
            check(java.security.MessageDigest.isEqual(enc, received.encKey) && java.security.MessageDigest.isEqual(mac, received.macKey)) {
                "Account key changed; preserve the existing local account"
            }
        } finally { enc.fill(0); mac.fill(0) }
    }

    private fun verifyAccountMutationRecovery(original: BitwardenVault, environment: BitwardenEnvironment,
        result: LoginResult.Success, profile: ProfileResponse): BitwardenAccountMutationRecord {
        val record = requireNotNull((accountMutations.restore(original.id) as? BitwardenAccountMutationStored.Present)?.record)
        check(!(record.kind == BitwardenAccountMutationKind.DELETE && record.phase == BitwardenAccountMutationPhase.CONFIRMED)) {
            "Deleted account is a preserved local copy"
        }
        checkCandidateIdentity(original, profile)
        check(profile.id == record.binding.userId && !profile.securityStamp.isNullOrBlank())
        val preserved = SymmetricCryptoKey(
            Base64.decode(decryptFromStorage(requireNotNull(original.encryptedEncKey)), Base64.NO_WRAP),
            Base64.decode(decryptFromStorage(requireNotNull(original.encryptedMacKey)), Base64.NO_WRAP))
        try {
            check(BitwardenAccountMutationProtocol().recoveryKeyMatches(record,
                accountMutationBinding(original.copy(userId = profile.id), environment), result.symmetricKey, preserved))
            val wrapper = requireNotNull(profile.key)
            val expectedKdf = when (wrapper) {
                record.newWrappedUserKey -> record.newKdf
                record.oldWrappedUserKey -> {
                    check(record.phase != BitwardenAccountMutationPhase.CONFIRMED)
                    record.oldKdf
                }
                else -> error("Account wrapper changed independently")
            }
            check(BitwardenAccountMutationKdf(result.kdfType, result.kdfIterations,
                result.kdfMemory, result.kdfParallelism) == expectedKdf)
            val bytes = BitwardenCrypto.decrypt(wrapper, result.stretchedKey)
            val expected = preserved.encKey + preserved.macKey
            try { check(java.security.MessageDigest.isEqual(bytes, expected)) }
            finally { bytes.fill(0); expected.fill(0) }
            return record
        } finally { preserved.clear() }
    }

    private fun finishAccountMutationRecovery(record: BitwardenAccountMutationRecord) {
        check(accountMutations.acknowledgeVerifiedRecovery(record))
    }

    suspend fun getAllVaults(): List<BitwardenVault> = withContext(Dispatchers.IO) {
        vaultDao.getAllVaults()
    }

    fun getAllVaultsFlow(): Flow<List<BitwardenVault>> = vaultDao.getAllVaultsFlow()
    
    /**
     * 获取活跃的 Vault
     */
    suspend fun getActiveVault(): BitwardenVault? = withContext(Dispatchers.IO) {
        val activeVaultId = (securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1) ?: -1)
        if (activeVaultId > 0) {
            vaultDao.getVaultById(activeVaultId)
        } else {
            vaultDao.getDefaultVault() ?: vaultDao.getAllVaults().firstOrNull()
        }
    }
    
    /**
     * 设置活跃的 Vault
     */
    fun setActiveVault(vaultId: Long) {
        synchronized(selectionMonitor) {
            selectionEpoch++
            securePrefs?.edit()?.putLong(KEY_ACTIVE_VAULT_ID, vaultId)?.apply()
        }
    }
    
    /**
     * 获取 Vault 的解锁状态
     */
    fun isVaultUnlocked(vaultId: Long): Boolean {
        return symmetricKeyCache.containsKey(vaultId) && (accessTokenCache.containsKey(vaultId) || isAccountMutationBlocked(vaultId)) &&
            vaultAppGenerations[vaultId] == takagi.ru.monica.security.SessionManager.lockGeneration.value
    }

    /**
     * 返回最近一次同步记录的 Premium 状态。
     *
     * 附件调用方必须使用保守的缓存状态，避免在未获授权的账户上绕过服务端能力检查。
     */
    fun isVaultPremium(vaultId: Long): Boolean {
        return BitwardenVaultPremiumStore.isPremium(context, vaultId)
    }

    fun getCachedSymmetricKey(vaultId: Long): SymmetricCryptoKey? {
        if (!isVaultUnlocked(vaultId)) return null
        val key = symmetricKeyCache[vaultId] ?: return null
        return SymmetricCryptoKey(
            encKey = key.encKey.copyOf(),
            macKey = key.macKey.copyOf()
        )
    }

    /**
     * 为附件子系统构建 [takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext]。
     *
     * 调用方（UI/ViewModel）通过此方法获取上下文，无需直接接触缓存的 token 或 API 工厂。
     * 返回 null 表示 vault 未解锁或 cipherId 缺失。
     *
     * @param vault BitwardenVault 实体（调用方通常已通过 Flow 持有）
     * @param cipherId Bitwarden cipherId（来自 [takagi.ru.monica.data.PasswordEntry.bitwardenCipherId]）
     */
    fun getAttachmentBitwardenContext(
        vault: BitwardenVault,
        cipherId: String?
    ): takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext? {
        if (cipherId.isNullOrBlank() || !isVaultUnlocked(vault.id)) return null
        val accessToken = accessTokenCache[vault.id] ?: return null
        val wrappingKey = symmetricKeyCache[vault.id] ?: return null
        val requireCurrent = attachmentGuard(vault, wrappingKey, accessToken)
        val vaultApi = apiManager.getVaultApi(vault)
        val httpClient = apiManager.getOkHttpClient(vault)
        val isOnline = isNetworkAvailable()
        return takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext(
            vaultApi = vaultApi,
            httpClient = httpClient,
            accessToken = accessToken,
            cipherId = cipherId,
            wrappingKey = SymmetricCryptoKey(
                encKey = wrappingKey.encKey.copyOf(),
                macKey = wrappingKey.macKey.copyOf()
            ),
            isOnline = isOnline,
            wrappingKeyProvider = {
                withContext(Dispatchers.IO) {
                    requireCurrent()
                    val resolved = takagi.ru.monica.bitwarden.service.BitwardenAttachmentKeyResolver.resolve(
                        vaultApi, accessToken, cipherId, wrappingKey)
                    try { requireCurrent(); resolved }
                    catch (error: Exception) { resolved.clear(); throw error }
                }
            },
            clientForUrl = { raw -> requireCurrent(); apiManager.getTransferClient(vault, raw) },
            requireCurrent = requireCurrent,
            withPublication = { action -> publishAttachment(vault, requireCurrent, action) },
            recoveryBinding = { owner -> takagi.ru.monica.attachments.executor.BitwardenAttachmentRecoveryStore.binding(vault, cipherId, owner) },
        )
    }

    /**
     * Fetches the current cipher before recovering an attachment on another device.
     * Modern Bitwarden ciphers may use a per-item key, so the vault key alone is not
     * sufficient for decrypting attachment metadata or the wrapped attachment key.
     */
    suspend fun fetchAttachmentCipherSnapshot(
        vault: BitwardenVault,
        cipherId: String
    ): AttachmentCipherSnapshot? = withContext(Dispatchers.IO) {
        if (cipherId.isBlank() || !isNetworkAvailable()) return@withContext null
        val accessToken = accessTokenCache[vault.id] ?: return@withContext null
        val vaultKey = symmetricKeyCache[vault.id] ?: return@withContext null
        val requireCurrent = attachmentGuard(vault, vaultKey, accessToken)
        requireCurrent()
        val vaultApi = apiManager.getVaultApi(vault)
        val response = runCatching {
            vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
        }.getOrNull() ?: return@withContext null
        if (!response.isSuccessful) return@withContext null
        val cipher = response.body() ?: return@withContext null
        val effectiveKey = BitwardenCipherKeyResolver.resolveRemoteCipherKey(vaultApi, accessToken, cipher, vaultKey)
        try {
            requireCurrent()
            AttachmentCipherSnapshot(
                context = takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext(
                    vaultApi = vaultApi,
                    httpClient = apiManager.getOkHttpClient(vault),
                    accessToken = accessToken,
                    cipherId = cipherId,
                    wrappingKey = SymmetricCryptoKey(
                        encKey = effectiveKey.encKey.copyOf(),
                        macKey = effectiveKey.macKey.copyOf()
                    ),
                    isOnline = true,
                    clientForUrl = { raw -> requireCurrent(); apiManager.getTransferClient(vault, raw) },
                    requireCurrent = requireCurrent,
                    withPublication = { action -> publishAttachment(vault, requireCurrent, action) },
                    recoveryBinding = { owner -> takagi.ru.monica.attachments.executor.BitwardenAttachmentRecoveryStore.binding(vault, cipherId, owner) },
                ),
                attachments = BitwardenAttachmentMetadataDecoder.decodeForStorage(
                    attachments = cipher.attachments.orEmpty(),
                    effectiveKey = effectiveKey
                )
            )
        } finally {
            BitwardenCipherKeyResolver.clearIfDerived(effectiveKey, vaultKey)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * 登录 Bitwarden
     */
    suspend fun login(
        serverUrl: String?,
        email: String,
        masterPassword: String,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null,
        environment: BitwardenEnvironment? = null,
        reauthentication: ReauthenticationContext? = null,
    ): RepositoryLoginResult = withContext(Dispatchers.IO) {
        val loginAppGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        try {
            val normalizedEmail = email.trim()
            Log.d(TAG, "开始登录 Bitwarden")
            
            val requestEnvironment = environment ?: requireNotNull(BitwardenEnvironmentResolver.resolveLegacy(
                BitwardenLegacyEnvironmentSnapshot(serverUrl?.takeIf { it.isNotBlank() }
                    ?: BitwardenApiFactory.OFFICIAL_VAULT_URL, tls = tlsConfig ?: BitwardenTlsConfig()),
                java.util.UUID.randomUUID().toString(),
            ).value) { "Bitwarden transport configuration rejected" }
            val effectiveServerUrl = requestEnvironment.base.toString()
            
            val scope = authenticationScope(effectiveServerUrl, normalizedEmail)
            suspend fun canRegister(): Boolean {
                if (!takagi.ru.monica.security.SessionManager.isUnlocked.value ||
                    takagi.ru.monica.security.SessionManager.lockGeneration.value != loginAppGeneration) return false
                if (reauthentication == null) return true
                if (!isReauthenticationCurrent(reauthentication)) return false
                val target = vaultDao.getVaultById(reauthentication.vaultId) ?: return false
                return BitwardenEnvironmentResolver.validateEndpoint(target.serverUrl,
                    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.BASE).value == requestEnvironment.base &&
                    BitwardenVaultIdentity.resolveCanonicalEmail(target) == BitwardenVaultIdentity.canonicalizeEmail(normalizedEmail) &&
                    isReauthenticationCurrent(reauthentication)
            }
            check(canRegister()) { "Bitwarden reauthentication session changed" }
            val loginEpoch = requireNotNull(BitwardenAuthSessionGate.beginIf(scope, ::canRegister) {
                authenticationGenerations[requestEnvironment.revision] = loginAppGeneration
                reauthentication?.let { authenticationEditGuards[requestEnvironment.revision] = it }
                authenticationEnvironments.put(scope, requestEnvironment)?.takeIf { it !== requestEnvironment }
                    ?.let(::retireAuthenticationEnvironment)
            }) { "Bitwarden login replaced" }
            requireAuthenticationTransportCurrent(requestEnvironment, loginAppGeneration)
            val rememberedScope = rememberedEnvironmentScope(scope, requestEnvironment)
            val rememberedToken = rememberedTwoFactorStore.read(rememberedScope)
            var loginResult = authService.login(
                email = normalizedEmail,
                password = masterPassword,
                serverUrl = effectiveServerUrl,
                captchaResponse = captchaResponse,
                tlsConfig = tlsConfig,
                rememberedTwoFactorToken = rememberedToken,
                environment = requestEnvironment,
            )

            // A rejected remembered token must not strand the account. Network/KDF failures
            // retain it; a server/account rejection retries the ordinary password flow once.
            val error = loginResult.exceptionOrNull()
            if (rememberedToken != null && error != null &&
                error !is CancellationException && error !is BitwardenKdfMemoryException &&
                classifyBitwardenLoginError(error) == null) {
                rememberedTwoFactorStore.clear(rememberedScope)
                loginResult = authService.login(normalizedEmail, masterPassword, effectiveServerUrl,
                    captchaResponse = captchaResponse, environment = requestEnvironment)
            }
            
            loginResult.fold(
                onSuccess = { result ->
                    when (result) {
                        is LoginResult.Success -> {
                            handleSuccessfulLogin(result, normalizedEmail, tlsConfig, loginEpoch,
                                expectedAppGeneration = loginAppGeneration)
                        }
                        is LoginResult.TwoFactorRequired -> {
                            rememberedTwoFactorStore.clear(rememberedScope)
                            RepositoryLoginResult.TwoFactorRequired(
                                providers = result.providers,
                                state = result.copy(authSessionEpoch = loginEpoch, authAppGeneration = loginAppGeneration)
                            )
                        }
                        is LoginResult.CaptchaRequired -> {
                            RepositoryLoginResult.CaptchaRequired(
                                message = result.message,
                                siteKey = result.siteKey
                            )
                        }
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "登录失败")
                    RepositoryLoginResult.Error(describeLoginError(error))
                }
            )
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "登录 KDF 内存不足")
            RepositoryLoginResult.Error(describeLoginError(e))
        } catch (e: Exception) {
            Log.e(TAG, "登录异常")
            RepositoryLoginResult.Error(describeLoginError(e))
        }
    }
    
    /**
     * 使用两步验证登录
     */
    suspend fun loginWithTwoFactor(
        twoFactorState: LoginResult.TwoFactorRequired,
        twoFactorCode: String,
        twoFactorProvider: Int,
        serverUrl: String?,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null,
        remember: Boolean = false
    ): RepositoryLoginResult = withContext(Dispatchers.IO) {
        try {
            val effectiveServerUrl = twoFactorState.environment?.base?.toString() ?: serverUrl?.takeIf { it.isNotBlank() }
                ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
            val scope = authenticationScope(effectiveServerUrl, twoFactorState.email)
            val loginEpoch = twoFactorState.authSessionEpoch ?: BitwardenAuthSessionGate.begin(scope)
            check(BitwardenAuthSessionGate.publish(scope, loginEpoch) { true } == true) { "Bitwarden login replaced" }
            twoFactorState.environment?.let { requireAuthenticationTransportCurrent(it,
                twoFactorState.authAppGeneration ?: takagi.ru.monica.security.SessionManager.lockGeneration.value) }
            
            val loginResult = if (twoFactorProvider == BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE) {
                authService.loginNewDeviceOtp(
                    twoFactorState = twoFactorState,
                    newDeviceOtp = twoFactorCode,
                    serverUrl = effectiveServerUrl,
                    captchaResponse = captchaResponse,
                    tlsConfig = tlsConfig
                )
            } else {
                authService.loginTwoFactor(
                    twoFactorState = twoFactorState,
                    twoFactorCode = twoFactorCode,
                    twoFactorProvider = twoFactorProvider,
                    remember = remember,
                    serverUrl = effectiveServerUrl,
                    captchaResponse = captchaResponse,
                    tlsConfig = tlsConfig
                )
            }
            
            loginResult.fold(
                onSuccess = { result ->
                    when (result) {
                        is LoginResult.Success -> handleSuccessfulLogin(result, twoFactorState.email, tlsConfig,
                            loginEpoch, rememberTwoFactor = remember && twoFactorProvider != BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE,
                            expectedAppGeneration = twoFactorState.authAppGeneration ?: takagi.ru.monica.security.SessionManager.lockGeneration.value)
                        is LoginResult.CaptchaRequired -> RepositoryLoginResult.CaptchaRequired(
                            message = result.message,
                            siteKey = result.siteKey
                        )
                        is LoginResult.TwoFactorRequired -> RepositoryLoginResult.TwoFactorRequired(
                            providers = result.providers,
                            state = result.copy(authSessionEpoch = loginEpoch, authAppGeneration = twoFactorState.authAppGeneration)
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "两步验证登录失败")
                    RepositoryLoginResult.Error(describeLoginError(error))
                }
            )
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "两步验证 KDF 内存不足")
            RepositoryLoginResult.Error(describeLoginError(e))
        } catch (e: Exception) {
            Log.e(TAG, "两步验证异常")
            RepositoryLoginResult.Error(describeLoginError(e))
        }
    }

    internal fun describeLoginError(error: Throwable): String {
        if (error is CancellationException) throw error
        // Use the same current-language cache as activities. The shared settings flow can
        // replay the previous language immediately after an update, unlike this cache.
        val localizedContext = LocaleHelper.setLocale(context, StartupLanguageCache.read(context))
        val kind = classifyBitwardenLoginError(error)
        return if (kind != null) localizedContext.getString(kind.messageRes)
        else parseErrorMessage(error.message, StringResolver { id, args -> localizedContext.getString(id, *args) })
    }

    suspend fun sendTwoFactorEmailLogin(
        twoFactorState: LoginResult.TwoFactorRequired,
        serverUrl: String?,
        tlsConfig: BitwardenTlsConfig? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val effectiveServerUrl = twoFactorState.environment?.base?.toString() ?: serverUrl?.takeIf { it.isNotBlank() }
            ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
        authService.sendTwoFactorEmailLogin(
            twoFactorState = twoFactorState,
            serverUrl = effectiveServerUrl,
            tlsConfig = tlsConfig
        )
    }

    suspend fun resendNewDeviceOtp(
        twoFactorState: LoginResult.TwoFactorRequired,
        serverUrl: String?,
        tlsConfig: BitwardenTlsConfig? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val effectiveServerUrl = twoFactorState.environment?.base?.toString() ?: serverUrl?.takeIf { it.isNotBlank() } ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
        val scope = authenticationScope(effectiveServerUrl, twoFactorState.email)
        val epoch = twoFactorState.authSessionEpoch ?: throw CancellationException("Login replaced")
        suspend fun requireCurrent() {
            currentCoroutineContext().ensureActive()
            if (BitwardenAuthSessionGate.publish(scope, epoch) { true } != true) {
                throw CancellationException("Login replaced")
            }
        }
        requireCurrent()
        val result = authService.resendNewDeviceOtp(twoFactorState, effectiveServerUrl, tlsConfig)
        requireCurrent()
        result
    }
    
    /**
     * 处理成功登录
     */
    private suspend fun handleSuccessfulLogin(
        result: LoginResult.Success,
        email: String,
        tlsConfig: BitwardenTlsConfig?,
        loginEpoch: Long,
        rememberTwoFactor: Boolean? = null,
        expectedAppGeneration: Long,
    ): RepositoryLoginResult {
        val environment = result.environment ?: run { result.clearKeys(); error("Bitwarden login environment missing") }
        var keysPublished = false
        try {
        val scope = authenticationScope(environment.base.toString(), email)
        val rememberedScope = rememberedEnvironmentScope(scope, environment)
        fun requireCurrent() {
            requireAuthenticationTransportCurrent(environment, expectedAppGeneration)
        }
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val original = findLoginVault(environment, email)
        authenticationEditGuards[environment.revision]?.let { check(original?.id == it.vaultId) }
        var candidateProfile: ProfileResponse? = null
        if (original != null) {
            // An existing vault keeps its Base/ownership. Changed destinations require a fresh-token profile check.
            val originalBase = BitwardenEnvironmentResolver.validateEndpoint(original.serverUrl,
                takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.BASE).value
            check(originalBase == environment.base) { "Bitwarden account destination changed" }
            val installed = runCatching { transportEnvironments.resolve(original) }.getOrNull()
            if (installed?.fingerprint != environment.fingerprint || isAccountMutationBlocked(original.id)) {
                val response = apiManager.getVaultApi(environment).profile("Bearer ${result.accessToken}")
                response.errorBody()?.close()
                currentCoroutineContext().ensureActive()
                requireCurrent()
                candidateProfile = response.body().takeIf { response.isSuccessful }
                    ?: error("Bitwarden candidate account verification failed")
                checkCandidateIdentity(original, candidateProfile)
            }
        }
        var recovery: BitwardenAccountMutationRecord? = null
        var preserveTransportBinding = false
        suspend fun publish(): RepositoryLoginResult.Success? = BitwardenAuthSessionGate.publish(scope, loginEpoch) {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            val published = handleSuccessfulLoginCurrent(result, email, original, candidateProfile, expectedAppGeneration,
                ::requireCurrent, recovery, preserveTransportBinding) { keysPublished = true }
            if (rememberTwoFactor != null) {
                rememberedTwoFactorStore.save(rememberedScope, rememberTwoFactor, result.twoFactorToken)
            } else if (result.twoFactorToken != null && rememberedTwoFactorStore.read(rememberedScope) != null) {
                rememberedTwoFactorStore.save(rememberedScope, true, result.twoFactorToken)
            }
            result.masterKey.fill(0)
            result.stretchedKey.clear()
            apiManager.retireEnvironment(environment)
            authenticationEnvironments.remove(scope, environment)
            published
        }
        val published = if (original != null) syncMutexForVault(original.id).withLock {
            // Re-read after serialization. Same-email login must not overwrite another server user,
            // and a profile obtained before a concurrent account change is not recovery evidence.
            val response = apiManager.getVaultApi(environment).profile("Bearer ${result.accessToken}")
            response.errorBody()?.close()
            requireCurrent()
            candidateProfile = requireNotNull(response.body().takeIf { response.isSuccessful })
            checkCandidateIdentity(original, requireNotNull(candidateProfile))
            val installed = transportEnvironments.resolve(original)
            preserveTransportBinding = original.transportSecretRef != null && installed.fingerprint == environment.fingerprint
            if (preserveTransportBinding) verifyPreservedAccountKey(original, result.symmetricKey)
            if (isAccountMutationBlocked(original.id)) {
                check(preserveTransportBinding) { "Account recovery cannot replace its server configuration" }
                recovery = verifyAccountMutationRecovery(original, environment, result, requireNotNull(candidateProfile))
            }
            publish()
        } else publish()
        return published ?: run {
            result.clearKeys()
            RepositoryLoginResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
        }
        } finally {
            result.masterKey.fill(0)
            result.stretchedKey.clear()
            if (!keysPublished) result.symmetricKey.clear()
            retireAuthenticationEnvironment(environment)
        }
    }

    private fun requireAuthenticationTransportCurrent(environment: BitwardenEnvironment, appGeneration: Long) {
        check(takagi.ru.monica.security.SessionManager.isUnlocked.value &&
            takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration &&
            authenticationGenerations[environment.revision] == appGeneration &&
            authenticationEditGuards[environment.revision]?.let(::isReauthenticationCurrent) != false &&
            apiManager.isEnvironmentActive(environment)) { "Bitwarden login session changed" }
    }

    private suspend fun findLoginVault(environment: BitwardenEnvironment, email: String): BitwardenVault? {
        val canonicalEmail = BitwardenVaultIdentity.canonicalizeEmail(email)
        val accountKey = BitwardenVaultIdentity.buildAccountKey(environment.base.toString(), null, canonicalEmail)
        return vaultDao.getVaultByAccountKey(accountKey)
            ?: vaultDao.getVaultByServerAndCanonicalEmail(environment.base.toString(), canonicalEmail)
    }

    private fun checkCandidateIdentity(original: BitwardenVault, profile: ProfileResponse) {
        check(BitwardenVaultIdentity.canonicalizeEmail(profile.email.orEmpty()) == BitwardenVaultIdentity.resolveCanonicalEmail(original) &&
            !profile.id.isNullOrBlank() && (original.userId.isNullOrBlank() || original.userId == profile.id)) {
            "Bitwarden candidate account identity mismatch"
        }
    }

    private suspend fun handleSuccessfulLoginCurrent(
        result: LoginResult.Success,
        email: String,
        original: BitwardenVault?,
        candidateProfile: ProfileResponse?,
        expectedAppGeneration: Long,
        requireCurrent: () -> Unit,
        recovery: BitwardenAccountMutationRecord?,
        preserveTransportBinding: Boolean,
        onKeysPublished: () -> Unit,
    ): RepositoryLoginResult.Success {
        val environment = requireNotNull(result.environment)
        val displayEmail = email.trim()
        val canonicalEmail = BitwardenVaultIdentity.canonicalizeEmail(displayEmail)
        val accountKey = if (preserveTransportBinding) requireNotNull(original).accountKey
            else BitwardenVaultIdentity.buildAccountKey(environment.base.toString(), null, canonicalEmail)
        var existing = findLoginVault(environment, email)
        check(existing?.id == original?.id && existing?.transportRevision == original?.transportRevision &&
            existing?.transportSecretRef == original?.transportSecretRef) { "Bitwarden account configuration changed" }
        if (existing != null && candidateProfile != null) checkCandidateIdentity(existing, candidateProfile)
        currentCoroutineContext().ensureActive()
        requireCurrent()
        if (existing == null) {
            // A crash before protected publication leaves a locked row with no credential material.
            val reservation = BitwardenVault(email = displayEmail, canonicalEmail = canonicalEmail, accountKey = accountKey,
                serverUrl = environment.base.toString(), apiUrl = environment.api.toString(), identityUrl = environment.identity.toString(),
                isLocked = true, isConnected = false)
            existing = reservation.copy(id = vaultDao.insert(reservation))
        }
        val reserved = existing
        val oldEnvironment = runCatching { transportEnvironments.resolve(reserved) }.getOrNull()
        val savedEnvironment = if (oldEnvironment?.fingerprint == environment.fingerprint && reserved.transportSecretRef != null) {
            requireNotNull(BitwardenEnvironmentResolver.resolve(environment.toDraft(), reserved.transportRevision).value)
        } else environment
        val binding = if (preserveTransportBinding) BitwardenVaultEnvironmentStore.binding(reserved)
            else transportSecrets.stage(reserved.id, savedEnvironment)
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val expiresAt = System.currentTimeMillis() + result.expiresIn * 1000L
        val saved = reserved.copy(
            email = displayEmail, canonicalEmail = canonicalEmail, accountKey = accountKey,
            serverUrl = savedEnvironment.base.toString(), identityUrl = savedEnvironment.identity.toString(), apiUrl = savedEnvironment.api.toString(),
            environmentPreset = savedEnvironment.preset.name, webVaultUrl = savedEnvironment.web.toString().takeIf { savedEnvironment.webExplicit },
            notificationsUrl = savedEnvironment.notifications.toString(), transportRevision = binding.revision, transportSecretRef = binding.reference,
            encryptedAccessToken = encryptForStorage(result.accessToken),
            encryptedRefreshToken = result.refreshToken?.let(::encryptForStorage), accessTokenExpiresAt = expiresAt,
            encryptedMasterKey = encryptForStorage(Base64.encodeToString(result.masterKey, Base64.NO_WRAP)),
            encryptedEncKey = encryptForStorage(Base64.encodeToString(result.symmetricKey.encKey, Base64.NO_WRAP)),
            encryptedMacKey = encryptForStorage(Base64.encodeToString(result.symmetricKey.macKey, Base64.NO_WRAP)),
            kdfType = result.kdfType, kdfIterations = result.kdfIterations, kdfMemory = result.kdfMemory, kdfParallelism = result.kdfParallelism,
            revisionDate = null, isLocked = false, isConnected = true,
            isDefault = reserved.isDefault || vaultDao.getDefaultVault() == null, updatedAt = System.currentTimeMillis(),
        )
        val callerContext = currentCoroutineContext()
        check(takagi.ru.monica.security.SessionManager.publishAtGeneration(expectedAppGeneration) {
            callerContext.ensureActive()
            requireCurrent()
            // Synchronous short SQL publication shares the main-session lock boundary; no network/crypto runs here.
            database.runInTransaction {
                val current = vaultDao.getVaultForTransportPublication(reserved.id)
                check(current != null && current.transportRevision == reserved.transportRevision &&
                    current.transportSecretRef == reserved.transportSecretRef && current.serverUrl == reserved.serverUrl &&
                    current.accountKey == reserved.accountKey) { "Bitwarden account changed before publication" }
                database.attachmentDao().resetRetiredBitwardenDownloads(saved.id)
                vaultDao.publishTransport(saved)
                callerContext.ensureActive()
                requireCurrent()
            }
            // SQL is committed before removing the durable gate. A crash/failure retains a frozen,
            // recoverable row; it cannot reopen an older credential publication.
            if (recovery != null) finishAccountMutationRecovery(recovery)
            else check(!isAccountMutationBlocked(saved.id)) { "Account requires recovery" }
            symmetricKeyCache[saved.id] = result.symmetricKey
            accessTokenCache[saved.id] = result.accessToken
            authenticationEditGuards.computeIfPresent(environment.revision) { _, previous ->
                previous.copy(vaultGeneration = getVaultSessionGeneration(saved.id))
            }
            onKeysPublished()
            true
        } == true) { "Bitwarden login session changed" }
        setActiveVault(saved.id)
        currentCoroutineContext().ensureActive()
        requireCurrent()
        // Retire only a known old reference after publication and key-generation revocation.
        if (reserved.transportSecretRef != null && reserved.transportSecretRef != saved.transportSecretRef) {
            runCatching {
                transportSecrets.deleteIfUnreferenced(BitwardenVaultEnvironmentStore.binding(reserved)) { ref ->
                    vaultDao.getTransportState(saved.id)?.reference == ref
                }
            }
        }
        return RepositoryLoginResult.Success(saved)
    }

    /**
     * 解锁已登录的 Vault
     */
    suspend fun unlock(vaultId: Long, masterPassword: String): UnlockResult = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId) ?: return@withContext UnlockResult.Error(strings.get(R.string.bitwarden_message_vault_missing))
            val unlockAppGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            val unlockEpoch = BitwardenAuthSessionGate.begin(authenticationScope(vault.serverUrl, vault.email))
            val canonicalEmail = BitwardenVaultIdentity.resolveCanonicalEmail(vault)
            val normalizedIterations = when (vault.kdfType) {
                BitwardenVault.KDF_TYPE_PBKDF2 -> vault.kdfIterations.takeIf { it > 0 } ?: PBKDF2_DEFAULT_ITERATIONS
                BitwardenVault.KDF_TYPE_ARGON2ID -> vault.kdfIterations.takeIf { it > 0 } ?: ARGON2_DEFAULT_ITERATIONS
                else -> vault.kdfIterations
            }
            val normalizedMemory = vault.kdfMemory.takeIf { it != null && it > 0 } ?: ARGON2_DEFAULT_MEMORY_MB
            val normalizedParallelism = vault.kdfParallelism.takeIf { it != null && it > 0 } ?: ARGON2_DEFAULT_PARALLELISM
            
            // 派生主密钥
            val masterKey = when (vault.kdfType) {
                BitwardenVault.KDF_TYPE_ARGON2ID -> {
                    BitwardenCrypto.deriveMasterKeyArgon2(
                        password = masterPassword,
                        salt = canonicalEmail,
                        iterations = normalizedIterations,
                        memory = normalizedMemory,
                        parallelism = normalizedParallelism
                    )
                }

                BitwardenVault.KDF_TYPE_PBKDF2 -> {
                    BitwardenCrypto.deriveMasterKeyPbkdf2(
                        password = masterPassword,
                        salt = canonicalEmail,
                        iterations = normalizedIterations
                    )
                }

                else -> {
                    return@withContext UnlockResult.Error(strings.get(R.string.bitwarden_message_kdf_unsupported, vault.kdfType))
                }
            }

            try {
                val storedMasterKey = vault.encryptedMasterKey?.let { decryptFromStorage(it) }
                    ?: return@withContext UnlockResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
                val derivedMasterKey = Base64.encodeToString(masterKey, Base64.NO_WRAP)
                if (storedMasterKey != derivedMasterKey) {
                    return@withContext UnlockResult.Error(strings.get(R.string.ime_unlock_error))
                }
            
                // 尝试从存储中恢复密钥
                val storedEncKey = vault.encryptedEncKey?.let { decryptFromStorage(it) }
                val storedMacKey = vault.encryptedMacKey?.let { decryptFromStorage(it) }
                
                if (storedEncKey != null && storedMacKey != null) {
                    try {
                        val encKeyBytes = Base64.decode(storedEncKey, Base64.NO_WRAP)
                        val macKeyBytes = Base64.decode(storedMacKey, Base64.NO_WRAP)
                        val symmetricKey = SymmetricCryptoKey(encKeyBytes, macKeyBytes)
                        
                        val token = vault.encryptedAccessToken?.let(::decryptFromStorage)
                        if (!publishRecoveredVault(vault, symmetricKey, token, unlockAppGeneration, unlockEpoch)) {
                            return@withContext UnlockResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
                        }

                        return@withContext UnlockResult.Success
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.e(TAG, "密钥恢复失败，尝试重新登录")
                    }
                }
                
                // 密钥恢复失败，需要重新登录
                UnlockResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
            } finally {
                masterKey.fill(0)
            }
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "解锁 KDF 内存不足")
            UnlockResult.Error(strings.get(R.string.bitwarden_message_kdf_memory))
        } catch (e: Exception) {
            Log.e(TAG, "解锁异常")
            UnlockResult.Error(e.message ?: strings.get(R.string.bitwarden_message_unlock_failed))
        }
    }
    
    /**
     * 锁定 Vault
     * 如果开启了"永不锁定"选项，则不执行锁定
     */
    suspend fun lock(vaultId: Long) = withContext(Dispatchers.IO) {
        if (isNeverLockEnabled) {
            Log.d(TAG, "永不锁定已开启，跳过锁定 Vault: $vaultId")
            return@withContext
        }
        forceLock(vaultId)
        Log.d(TAG, "Vault 已锁定: $vaultId")
    }
    
    /**
     * 尝试从存储中恢复解锁状态（无需主密码）
     * 用于"永不锁定"模式下 App 重启后恢复
     * 
     * @return true 如果成功恢复解锁状态
     */
    suspend fun tryRestoreUnlockState(vaultId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId) ?: return@withContext false
            restoreUnlockStateFromVault(vault)
        } catch (e: Exception) {
            Log.e(TAG, "恢复解锁状态失败")
            false
        }
    }

    suspend fun restoreUnlockedVaults(): Set<Long> = withContext(Dispatchers.IO) {
        if (!isNeverLockEnabled) {
            return@withContext emptySet()
        }

        val restoredVaultIds = linkedSetOf<Long>()
        getAllVaults().forEach { vault ->
            if (restoreUnlockStateFromVault(vault)) {
                restoredVaultIds += vault.id
            }
        }
        restoredVaultIds
    }

    suspend fun forceLock(vaultId: Long) = withContext(Dispatchers.IO) {
        val vault = vaultDao.getVaultById(vaultId) ?: return@withContext
        val scope = authenticationScope(vault.serverUrl, vault.email)
        BitwardenAuthSessionGate.revokeIf(scope, predicate = { true }) {
            authenticationEnvironments.remove(scope)?.let(::retireAuthenticationEnvironment)
            symmetricKeyCache.remove(vaultId)
            accessTokenCache.remove(vaultId)
            database.attachmentDao().resetRetiredBitwardenDownloads(vaultId)
            vaultDao.setLocked(vaultId, true)
        }
        Log.d(TAG, "Vault 已强制锁定: $vaultId")
    }
    
    /**
     * 锁定所有 Vault
     * 如果开启了"永不锁定"选项，则不执行锁定
     */
    suspend fun lockAll() = withContext(Dispatchers.IO) {
        if (isNeverLockEnabled) {
            Log.d(TAG, "永不锁定已开启，跳过锁定所有 Vault")
            return@withContext
        }
        getAllVaults().forEach { vault -> forceLock(vault.id) }
        symmetricKeyCache.clear()
        accessTokenCache.clear()
        Log.d(TAG, "所有 Vault 已锁定")
    }
    
    /**
     * 登出并删除 Vault
     */
    suspend fun logout(vaultId: Long): Boolean = withContext(Dispatchers.IO) {
        val vault = vaultDao.getVaultById(vaultId) ?: return@withContext false
        val scope = authenticationScope(vault.serverUrl, vault.email)
        var success = false
        BitwardenAuthSessionGate.revoke(scope) {
            authenticationEnvironments.remove(scope)?.let(::retireAuthenticationEnvironment)
            rememberedTwoFactorStore.clearAccount(scope)
            accountIdentityStore.clear(vaultId)
            success = logoutCurrent(vaultId)
        }
        success
    }

    private suspend fun logoutCurrent(vaultId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // 清除缓存
            symmetricKeyCache.remove(vaultId)
            accessTokenCache.remove(vaultId)
            clearVaultSyncMutex(vaultId)
            BitwardenSendDraftStore(context).discardVaultAfterRevocation(vaultId)

            database.withTransaction {
                clearVaultLocalReferences(vaultId)

                // 最后删除 Vault 本体
                vaultDao.deleteById(vaultId)
            }
            // Only this separately confirmed local removal retires an account-change archive.
            accountMutationPrefs.edit().remove("bitwarden_account_mutation_v1_$vaultId").commit()

            // 重置活跃 Vault
            synchronized(selectionMonitor) {
                selectionEpoch++
                if ((securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1) ?: -1) == vaultId) {
                    securePrefs?.edit()?.remove(KEY_ACTIVE_VAULT_ID)?.apply()
                }
            }
            
            Log.d(TAG, "Vault 已登出: $vaultId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "登出失败")
            false
        }
    }

    private suspend fun clearVaultLocalReferences(vaultId: Long) {
        // 先清理所有直接引用 vault_id 的表，避免删除 vault 主记录时触发外键异常。
        pendingOpDao.deleteByVault(vaultId)
        conflictDao.deleteByVault(vaultId)
        sendDao.deleteByVault(vaultId)
        folderDao.deleteByVault(vaultId)
        rawEntryRecordDao.deleteByVault(vaultId)

        // 再清理业务数据与文件夹映射。
        passwordEntryDao.deleteAllByBitwardenVaultId(vaultId)
        secureItemDao.deleteAllByBitwardenVaultId(vaultId)
        passkeyDao.deleteAllByBitwardenVaultId(vaultId)
        categoryDao.unlinkByVaultId(vaultId)
    }
    
    // ==================== 同步 ====================
    
    /**
     * 执行完整同步
     * 
     * 同步流程：
     * 1. 先处理本地待删除操作（delete）
     * 2. 上传本地创建的条目到服务器（create）
     * 3. 上传本地修改的条目到服务器（update）
     * 4. 从服务器拉取最新数据（pull）
     */
    @Deprecated(
        message = "Use BitwardenRepository.syncViaCoordinator for external callers. Bare sync is the coordinator executor body and must not be used as a UI/worker entrypoint."
    )
    suspend fun sync(vaultId: Long): SyncResult = withContext(Dispatchers.IO) {
        syncMutexForVault(vaultId).withLock {
            var issueSession: takagi.ru.monica.bitwarden.service.BitwardenSyncIssues.Session? = null
            var issueFailure: takagi.ru.monica.bitwarden.service.SyncIssueReason? = null
            try {
                val vault = vaultDao.getVaultById(vaultId) ?: return@withLock SyncResult.Error(strings.get(R.string.bitwarden_message_vault_missing))

                if (!isVaultUnlocked(vaultId)) {
                    return@withLock SyncResult.Error(strings.get(R.string.legacy_ui_vault_locked))
                }

                val syncGeneration = getVaultSessionGeneration(vaultId)
                val syncAppGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
                val symmetricKey = symmetricKeyCache[vaultId] ?: return@withLock SyncResult.Error(strings.get(R.string.bitwarden_message_key_unavailable))
                var accessToken = accessTokenCache[vaultId] ?: return@withLock SyncResult.Error(strings.get(R.string.bitwarden_message_token_unavailable))

                fun currentIssueSession() = isVaultUnlocked(vaultId) &&
                    symmetricKeyCache[vaultId] === symmetricKey && getVaultSessionGeneration(vaultId) == syncGeneration
                issueSession = syncIssues.begin(vaultId, isCurrent = ::currentIssueSession)
                if (!currentIssueSession()) return@withLock SyncResult.Error(strings.get(R.string.legacy_ui_vault_locked))

                // 检查 Token 是否需要刷新
                val expiresAt = vault.accessTokenExpiresAt ?: 0
                if (expiresAt <= System.currentTimeMillis() + 60000) {
                    val refreshResult = refreshToken(vault)
                    if (refreshResult != null) {
                        accessToken = refreshResult
                    } else {
                        issueFailure = takagi.ru.monica.bitwarden.service.SyncIssueReason.AUTHENTICATION
                        return@withLock SyncResult.Error(strings.get(R.string.bitwarden_message_token_refresh_failed))
                    }
                }

                val preflight = apiManager.getVaultApi(vault).profile("Bearer $accessToken")
                val profile = preflight.body()
                if (!preflight.isSuccessful || profile == null) {
                    issueFailure = takagi.ru.monica.bitwarden.service.SyncIssueReason.from(preflight.code().toString())
                    return@withLock SyncResult.Error("Cannot verify the account session; local changes retained")
                }
                if (!validateBitwardenSessionProfile(vaultId, accessToken, profile) {
                    currentIssueSession() && syncAppGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value
                }) {
                    issueFailure = takagi.ru.monica.bitwarden.service.SyncIssueReason.AUTHENTICATION
                    return@withLock SyncResult.Error("The account session changed; sign in again")
                }

                // 1. 先处理本地待删除操作（delete）
                val processedDeleteCount = syncService.processPendingOperations(vault, accessToken, symmetricKey, issueSession)

                // 2. 再上传本地创建的条目到服务器（create）
                val uploadResult = syncService.uploadLocalEntries(vault, accessToken, symmetricKey, issueSession)
                val uploadedCount = when (uploadResult) {
                    is takagi.ru.monica.bitwarden.service.UploadResult.Success -> uploadResult.uploaded
                    else -> 0
                }
                val uploadFailedCount = when (uploadResult) {
                    is takagi.ru.monica.bitwarden.service.UploadResult.Success -> uploadResult.failed
                    else -> 0
                }

                // 3. 上传本地已修改的条目到服务器（update）
                val modifiedUploadResult = syncService.uploadModifiedEntries(vault, accessToken, symmetricKey, issueSession)
                val modifiedUploadedCount = when (modifiedUploadResult) {
                    is takagi.ru.monica.bitwarden.service.UploadResult.Success -> modifiedUploadResult.uploaded
                    else -> 0
                }
                val modifiedUploadFailedCount = when (modifiedUploadResult) {
                    is takagi.ru.monica.bitwarden.service.UploadResult.Success -> modifiedUploadResult.failed
                    else -> 0
                }

                // 4. 执行同步（pull）
                val result = syncService.fullSync(vault, accessToken, symmetricKey, issueSession)

                // 更新最后同步时间
                securePrefs?.edit()?.putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())?.apply()

                when (result) {
                    is ServiceSyncResult.Success -> {
                        val totalUploadedCount = uploadedCount + modifiedUploadedCount
                        val totalUploadFailedCount = uploadFailedCount + modifiedUploadFailedCount
                        val appliedChangeCount =
                            result.ciphersAdded +
                                result.ciphersUpdated +
                                totalUploadedCount +
                                processedDeleteCount
                        val availableOfflineCount = getAvailableOfflineSecretCount(vaultId)
                        val overallResult = if (totalUploadFailedCount > 0) {
                            "PARTIAL_SUCCESS"
                        } else {
                            "SUCCESS"
                        }
                        BitwardenDiagLogger.append(
                            "BitwardenRepository overallSyncResult: vaultId=$vaultId, result=$overallResult, " +
                                "uploaded=$uploadedCount, modifiedUploaded=$modifiedUploadedCount, " +
                                "uploadFailed=$totalUploadFailedCount, deletes=$processedDeleteCount, " +
                                "ciphersAdded=${result.ciphersAdded}, ciphersUpdated=${result.ciphersUpdated}, " +
                                "conflicts=${result.conflictsDetected}, skippedLocalDirty=${result.skippedDueToLocalDirty}, " +
                                "appliedChanges=$appliedChangeCount, availableOffline=$availableOfflineCount"
                        )
                        SyncResult.Success(
                            appliedChangeCount = appliedChangeCount,
                            remoteAddedCount = result.ciphersAdded,
                            remoteUpdatedCount = result.ciphersUpdated,
                            uploadedCount = totalUploadedCount,
                            deletedCount = processedDeleteCount,
                            availableOfflineCount = availableOfflineCount,
                            conflictCount = result.conflictsDetected,
                            uploadFailedCount = totalUploadFailedCount,
                            skippedDueToLocalDirtyCount = result.skippedDueToLocalDirty
                        )
                    }
                    is ServiceSyncResult.Error -> {
                        issueFailure = takagi.ru.monica.bitwarden.service.SyncIssueReason.from(result.message)
                        SyncResult.Error(result.message)
                    }
                    is ServiceSyncResult.EmptyVaultBlocked -> {
                        SyncResult.EmptyVaultBlocked(
                            vaultId = vaultId,
                            localCount = result.localCount,
                            serverCount = result.serverCount,
                            reason = result.reason
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                issueFailure = takagi.ru.monica.bitwarden.service.SyncIssueReason.from(e.javaClass.simpleName + " " + e.message)
                Log.e(TAG, "同步异常")
                SyncResult.Error(e.message ?: strings.get(R.string.bitwarden_message_sync_failed))
            } finally {
                issueSession?.finish(issueFailure)
            }
        }
    }

    suspend fun getVaultCacheRiskSummary(vaultId: Long): VaultCacheRiskSummary = withContext(Dispatchers.IO) {
        if (vaultDao.getVaultById(vaultId) == null) {
            throw IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing))
        }
        collectVaultCacheRiskSummary(vaultId)
    }

    suspend fun clearVaultLocalCache(
        vaultId: Long,
        mode: CacheClearMode
    ): CacheClearResult = withContext(Dispatchers.IO) {
        if (vaultDao.getVaultById(vaultId) == null) {
            throw IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing))
        }

        val cacheClearStartedAt = System.currentTimeMillis()
        val riskSummary = collectVaultCacheRiskSummary(vaultId)
        val protectedCipherIds = collectProtectedCipherIds(vaultId)
        val keepIds = if (protectedCipherIds.isEmpty()) {
            listOf(CACHE_KEEP_SENTINEL_CIPHER_ID)
        } else {
            protectedCipherIds.toList()
        }

        val passwordBefore = passwordEntryDao.getBitwardenEntryCount(vaultId)
        val secureBefore = secureItemDao.getBitwardenEntriesCount(vaultId)
        val passkeyBefore = passkeyDao.getByBitwardenVaultId(vaultId).size
        val folderBefore = folderDao.getFoldersByVault(vaultId).size
        val sendBefore = sendDao.count(vaultId)
        val conflictBefore = conflictDao.getUnresolvedConflictsByVault(vaultId).size
        val pendingBefore = pendingOpDao.getRunnableOperationsByVault(vaultId).size

        // Own transaction: a session change rolls back Send deletion without losing protected rows.
        if (mode == CacheClearMode.SAFE_ONLY_SYNCED) sendDao.clearSyncedCache(vaultId, cacheClearStartedAt)
        database.withTransaction {
            when (mode) {
                CacheClearMode.SAFE_ONLY_SYNCED -> {
                    passwordEntryDao.deleteBitwardenEntriesNotIn(vaultId, keepIds)
                    secureItemDao.deleteBitwardenEntriesNotIn(vaultId, keepIds)
                    passkeyDao.deleteBitwardenEntriesNotIn(vaultId, keepIds)
                    folderDao.deleteByVault(vaultId)
                }

                CacheClearMode.FULL_FORCE -> {
                    clearVaultLocalReferences(vaultId)
                }
            }
        }

        val passwordAfter = passwordEntryDao.getBitwardenEntryCount(vaultId)
        val secureAfter = secureItemDao.getBitwardenEntriesCount(vaultId)
        val passkeyAfter = passkeyDao.getByBitwardenVaultId(vaultId).size
        val folderAfter = folderDao.getFoldersByVault(vaultId).size
        val sendAfter = sendDao.count(vaultId)
        val conflictAfter = conflictDao.getUnresolvedConflictsByVault(vaultId).size
        val pendingAfter = pendingOpDao.getRunnableOperationsByVault(vaultId).size

        CacheClearResult(
            mode = mode,
            riskSummary = riskSummary,
            protectedCipherCount = protectedCipherIds.size,
            passwordClearedCount = (passwordBefore - passwordAfter).coerceAtLeast(0),
            secureItemClearedCount = (secureBefore - secureAfter).coerceAtLeast(0),
            passkeyClearedCount = (passkeyBefore - passkeyAfter).coerceAtLeast(0),
            folderClearedCount = (folderBefore - folderAfter).coerceAtLeast(0),
            sendClearedCount = (sendBefore - sendAfter).coerceAtLeast(0),
            unresolvedConflictClearedCount = (conflictBefore - conflictAfter).coerceAtLeast(0),
            pendingOperationClearedCount = (pendingBefore - pendingAfter).coerceAtLeast(0)
        )
    }

    private suspend fun collectVaultCacheRiskSummary(vaultId: Long): VaultCacheRiskSummary {
        val pendingOperations = pendingOpDao.getRunnableOperationsByVault(vaultId)
        val passwordLocalModified = passwordEntryDao.getEntriesWithPendingBitwardenSync(vaultId)
        val secureLocalModified = secureItemDao.getLocalModifiedEntries(vaultId)
        val unresolvedConflicts = conflictDao.getUnresolvedConflictsByVault(vaultId)

        return VaultCacheRiskSummary(
            vaultId = vaultId,
            pendingOperationCount = pendingOperations.size,
            passwordLocalModifiedCount = passwordLocalModified.size,
            secureItemLocalModifiedCount = secureLocalModified.size,
            unresolvedConflictCount = unresolvedConflicts.size
        )
    }

    private suspend fun collectProtectedCipherIds(vaultId: Long): Set<String> {
        val protected = linkedSetOf<String>()

        passwordEntryDao.getEntriesWithPendingBitwardenSync(vaultId).forEach { entry ->
            entry.bitwardenCipherId
                ?.takeIf { it.isNotBlank() }
                ?.let(protected::add)
        }

        secureItemDao.getLocalModifiedEntries(vaultId).forEach { item ->
            item.bitwardenCipherId
                ?.takeIf { it.isNotBlank() }
                ?.let(protected::add)
        }

        pendingOpDao.getRunnableOperationsByVault(vaultId).forEach { op ->
            op.bitwardenCipherId
                ?.takeIf { it.isNotBlank() }
                ?.let(protected::add)
        }

        conflictDao.getUnresolvedConflictsByVault(vaultId).forEach { conflict ->
            conflict.bitwardenCipherId
                ?.takeIf { it.isNotBlank() }
                ?.let(protected::add)
        }

        return protected
    }

    suspend fun repairHistoricalBitwardenTotp(vaultId: Long): Result<BitwardenHistoricalTotpRepairResult> =
        withContext(Dispatchers.IO) {
            try {
                val vault = vaultDao.getVaultById(vaultId)
                    ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

                if (!isVaultUnlocked(vaultId)) {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
                }

                val symmetricKey = symmetricKeyCache[vaultId]
                    ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_key_unavailable)))
                var accessToken = accessTokenCache[vaultId]
                    ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_unavailable)))

                val expiresAt = vault.accessTokenExpiresAt ?: 0
                if (expiresAt <= System.currentTimeMillis() + 60000) {
                    val refreshed = refreshToken(vault)
                    if (refreshed != null) {
                        accessToken = refreshed
                    } else {
                        return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                    }
                }

                val requireCurrent = attachmentGuard(vault, symmetricKey, accessToken)
                requireCurrent()
                Result.success(
                    historicalTotpRepairService.repairHistoricalTotp(
                        vault = vault,
                        accessToken = accessToken,
                        symmetricKey = symmetricKey,
                        requireCurrent = requireCurrent,
                        withPublication = { action -> publishAttachment(vault, requireCurrent, action) },
                    )
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Log.e(TAG, "修复历史 Bitwarden TOTP 失败")
                Result.failure(IllegalStateException(strings.get(R.string.bitwarden_account_operation_failed)))
            }
        }
    
    /** Captures key/lock/configuration identity; only access-token rotation is interchangeable. */
    private fun requestSession(vault: BitwardenVault): takagi.ru.monica.bitwarden.api.BitwardenAuthenticatedSession {
        val key = requireNotNull(symmetricKeyCache[vault.id]) { "Bitwarden account is locked" }
        val token = requireNotNull(accessTokenCache[vault.id]) { "Bitwarden account is locked" }
        val generation = getVaultSessionGeneration(vault.id)
        val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        val guard: () -> Unit = {
            check(isTransportCurrent(vault) && symmetricKeyCache[vault.id] === key &&
                getVaultSessionGeneration(vault.id) == generation &&
                takagi.ru.monica.security.SessionManager.lockGeneration.value == appGeneration) {
                "Bitwarden account request session changed"
            }
        }
        guard()
        val record = requestSessions.compute(vault.id) { _, existing ->
            if (existing != null && existing.key === key && existing.generation == generation &&
                existing.appGeneration == appGeneration && existing.transportRevision == vault.transportRevision &&
                existing.secretRef == vault.transportSecretRef) existing
            else {
                existing?.session?.retire()
                RequestSessionRecord(generation, appGeneration, key, vault.transportRevision, vault.transportSecretRef,
                    takagi.ru.monica.bitwarden.api.BitwardenAuthenticatedSession(token,
                        currentToken = { accessTokenCache[vault.id] }, requireCurrent = guard,
                        refresh = { rejected -> performTokenRefresh(vault, rejected, guard) }))
            }
        }
        return requireNotNull(record).session.also { it.checkCurrent() }
    }

    private fun isAccessTokenCurrent(vaultId: Long, token: String): Boolean =
        if (isAccountMutationBlocked(vaultId)) false
        else if (accessTokenCache[vaultId] == token) isVaultUnlocked(vaultId)
        else runCatching { requestSessions[vaultId]?.session?.accepts(token) == true }.getOrDefault(false)

    /** Expiry refresh reads the latest row after another caller may already have rotated credentials. */
    private suspend fun refreshToken(
        vault: BitwardenVault,
        publishIfCurrent: (suspend () -> Boolean)? = null,
        rejectedAccessToken: String? = null,
    ): String? {
        currentCoroutineContext().ensureActive()
        if (publishIfCurrent?.invoke() == false) return null
        val session = requestSession(vault)
        val token = accessTokenCache[vault.id] ?: return null
        val latest = vaultDao.getVaultById(vault.id) ?: return null
        session.checkCurrent()
        val refreshed = if (rejectedAccessToken == null &&
            (latest.accessTokenExpiresAt ?: 0) > System.currentTimeMillis() + 60_000L) token
        else session.refreshRejected(rejectedAccessToken ?: token)
        currentCoroutineContext().ensureActive()
        session.checkCurrent()
        if (publishIfCurrent?.invoke() == false) return null
        return refreshed
    }

    /** Called under this session's refresh mutex; network waits never hold a database/publication gate. */
    private suspend fun performTokenRefresh(
        original: BitwardenVault,
        previousToken: String,
        requireCurrent: () -> Unit,
    ): String? {
        requireCurrent()
        val vault = vaultDao.getVaultById(original.id) ?: return null
        val refreshToken = vault.encryptedRefreshToken?.let { decryptFromStorage(it) } ?: return null
        val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        fun current(): Boolean {
            requireCurrent()
            return isTransportCurrent(vault) && accessTokenCache[vault.id] == previousToken
        }
        return try {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            val result = authService.refreshToken(refreshToken = refreshToken, vault = vault).getOrNull() ?: return null
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            val encryptedAccess = encryptForStorage(result.accessToken)
            val encryptedRefresh = result.refreshToken?.let(::encryptForStorage)
            BitwardenAuthSessionGate.inspect(authenticationScope(vault.serverUrl, vault.email)) {
                if (!current()) return@inspect null
                val caller = currentCoroutineContext()
                takagi.ru.monica.security.SessionManager.publishAtGeneration(appGeneration) {
                    caller.ensureActive()
                    if (!current()) return@publishAtGeneration null
                    database.runInTransaction {
                        val latest = vaultDao.getVaultForTransportPublication(vault.id)
                        check(latest != null && latest.transportRevision == vault.transportRevision &&
                            latest.transportSecretRef == vault.transportSecretRef &&
                            latest.encryptedRefreshToken == vault.encryptedRefreshToken) { "Bitwarden refresh configuration changed" }
                        vaultDao.publishTransport(latest.copy(encryptedAccessToken = encryptedAccess,
                            encryptedRefreshToken = encryptedRefresh ?: latest.encryptedRefreshToken,
                            accessTokenExpiresAt = System.currentTimeMillis() + result.expiresIn * 1000L,
                            updatedAt = System.currentTimeMillis()))
                        caller.ensureActive()
                        check(current()) { "Bitwarden refresh session changed" }
                    }
                    accessTokenCache[vault.id] = result.accessToken
                    result.accessToken
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, "Bitwarden token refresh failed; previous state retained")
            null
        }
    }

    // ==================== 数据访问 ====================
    
    suspend fun getPasswordEntries(vaultId: Long): List<PasswordEntry> = withContext(Dispatchers.IO) {
        passwordEntryDao.getByBitwardenVaultId(vaultId)
    }
    
    suspend fun getFolders(vaultId: Long): List<BitwardenFolder> = withContext(Dispatchers.IO) {
        folderDao.getFoldersByVault(vaultId)
    }

    suspend fun createFolder(vaultId: Long, name: String): Result<BitwardenFolder> = withContext(Dispatchers.IO) {
        try {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException(strings.get(R.string.bitwarden_message_folder_name_required)))

            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            if (!isVaultUnlocked(vaultId)) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
            }

            val symmetricKey = symmetricKeyCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_key_unavailable)))
            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_unavailable)))

            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                }
            }

            val encryptedName = BitwardenCrypto.encryptString(trimmed, symmetricKey)
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createFolder(
                "Bearer $accessToken",
                FolderCreateRequest(name = encryptedName)
            )

            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(strings.get(R.string.bitwarden_message_create_http, response.code(), response.message()))
                )
            }

            val created = response.body()!!
            val folder = BitwardenFolder(
                vaultId = vaultId,
                bitwardenFolderId = created.id,
                name = trimmed,
                encryptedName = encryptedName,
                revisionDate = created.revisionDate ?: "",
                lastSyncedAt = System.currentTimeMillis()
            )
            folderDao.upsert(folder)
            Result.success(folder)
        } catch (e: Exception) {
            Log.e(TAG, "创建 Bitwarden 文件夹失败")
            Result.failure(e)
        }
    }

    suspend fun renameFolder(vaultId: Long, folderId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException(strings.get(R.string.bitwarden_message_folder_name_required)))

            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            if (!isVaultUnlocked(vaultId)) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
            }

            val symmetricKey = symmetricKeyCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_key_unavailable)))
            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_unavailable)))

            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                }
            }

            val encryptedName = BitwardenCrypto.encryptString(trimmed, symmetricKey)
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.updateFolder(
                "Bearer $accessToken",
                folderId,
                FolderUpdateRequest(name = encryptedName)
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(strings.get(R.string.bitwarden_message_rename_http, response.code(), response.message()))
                )
            }

            folderDao.updateName(folderId, trimmed, encryptedName)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "重命名 Bitwarden 文件夹失败")
            Result.failure(e)
        }
    }

    suspend fun deleteFolder(vaultId: Long, folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            if (!isVaultUnlocked(vaultId)) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
            }

            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_unavailable)))

            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                }
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.deleteFolder("Bearer $accessToken", folderId)
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(strings.get(R.string.bitwarden_message_delete_http, response.code(), response.message()))
                )
            }

            folderDao.deleteByBitwardenId(folderId)
            categoryDao.unlinkByFolderId(folderId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "删除 Bitwarden 文件夹失败")
            Result.failure(e)
        }
    }

    /** Bitwarden nesting is expressed by folder names; encrypted ciphers stay in this account. */
    internal suspend fun transferFolder(vaultId: Long, folderId: String, parentId: String?, copy: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                syncMutexForVault(vaultId).withLock {
                    val vault = requireNotNull(vaultDao.getVaultById(vaultId))
                    check(isVaultUnlocked(vaultId)) { strings.get(R.string.legacy_ui_vault_locked) }
                    val vaultKey = requireNotNull(symmetricKeyCache[vaultId])
                    var token = requireNotNull(accessTokenCache[vaultId])
                    if ((vault.accessTokenExpiresAt ?: 0) <= System.currentTimeMillis() + 60000) {
                        token = requireNotNull(refreshToken(vault)) { "Cannot refresh the account token" }
                    }
                    val api = apiManager.getVaultApi(vault)
                    val remote = api.sync("Bearer $token").let { check(it.isSuccessful) { "Cannot read server folders (${it.code()})" }; requireNotNull(it.body()) }
                    val folders = remote.folders.map { it.id to BitwardenCrypto.decryptToString(requireNotNull(it.name), vaultKey) }.toMap()
                    val names = takagi.ru.monica.bitwarden.service.planBitwardenFolderTransfer(
                        folders, folderId, parentId, copy, strings.get(R.string.copy))
                    val tree = names.keys.map { it to folders.getValue(it) }
                    if (!copy) {
                        // Membership and all cipher fields remain untouched by a name-based move.
                        val renamed = mutableListOf<Pair<String, String>>()
                        try {
                            tree.forEach { (id, oldName) ->
                                renameFolder(vaultId, id, names.getValue(id)).getOrThrow()
                                renamed += id to oldName
                            }
                        } catch (failure: Exception) {
                            // Best effort restore if a later HTTP request fails; no cipher is deleted.
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                renamed.asReversed().forEach { (id, oldName) ->
                                    renameFolder(vaultId, id, oldName).exceptionOrNull()?.let(failure::addSuppressed)
                                }
                            }
                            if (failure.suppressed.isNotEmpty()) error("Folder move failed and some folder names could not be restored. All source entries were retained: ${failure.message}")
                            throw failure
                        }
                    } else {
                        val localPasswords = passwordEntryDao.getAllPasswordEntriesSync() + passwordEntryDao.getDeletedEntriesSync()
                        val localItems = secureItemDao.getAllItemsIncludingArchived().first() + secureItemDao.getDeletedItemsSync()
                        check(localPasswords.none { it.bitwardenVaultId == vaultId && it.bitwardenFolderId in names && it.bitwardenLocalModified } &&
                            localItems.none { it.bitwardenVaultId == vaultId && it.bitwardenFolderId in names && it.bitwardenLocalModified } &&
                            passkeyDao.getByBitwardenVaultId(vaultId).none { it.bitwardenFolderId in names && it.syncStatus == "PENDING" } &&
                            pendingOpDao.getPendingOperationsByVault(vaultId).isEmpty()) {
                            "Sync pending account changes before copying this folder; the source is unchanged"
                        }
                        val ciphers = remote.ciphers.filter { it.folderId in names }
                        check(ciphers.none { !it.attachments.isNullOrEmpty() || !it.login?.fido2Credentials.isNullOrEmpty() }) {
                            "Folders with attachments or passkeys cannot be duplicated in Bitwarden; the source is unchanged"
                        }
                        val documents = ciphers.map { cipher ->
                            val response = api.getCipherDocument("Bearer $token", cipher.id)
                            check(response.isSuccessful) { "Cannot read source entry (${response.code()})" }
                            val document = requireNotNull(response.body())
                            check(takagi.ru.monica.bitwarden.service.folderCopyDocumentIsSafe(document, cipher.id, cipher.folderId)) {
                                "The source entry changed or contains attachments or passkeys; the source is unchanged"
                            }
                            cipher to document
                        }
                        // Preflight every source before creating any folder or entry.
                        val encryptionAccount = takagi.ru.monica.bitwarden.service.BitwardenCipherUploadProtocol.userId(vault, api, token)
                        val targets = tree.associate { (id, _) -> id to createFolder(vaultId, names.getValue(id)).getOrThrow().bitwardenFolderId }
                        documents.forEach { (cipher, document) ->
                            check(isVaultUnlocked(vaultId)) { strings.get(R.string.legacy_ui_vault_locked) }
                            val request = takagi.ru.monica.bitwarden.service.BitwardenCipherUploadProtocol.documentAccount(
                                takagi.ru.monica.bitwarden.service.folderCopyDocument(document, targets.getValue(cipher.folderId!!)), encryptionAccount)
                            val response = api.createCipherDocument("Bearer $token", request)
                            check(response.isSuccessful && response.body() != null) { "Folder copy incomplete (${response.code()}); the original folder and entries were retained" }
                            check(takagi.ru.monica.bitwarden.service.folderCopyPreserved(request, response.body()!!)) {
                                "Copied entry verification failed; the original folder and entries were retained"
                            }
                        }
                    }
                }
                when (val refreshed = sync(vaultId)) {
                    is SyncResult.Success -> Unit
                    is SyncResult.Error -> error("Transfer saved, but account refresh failed: ${refreshed.message}")
                    else -> error("Transfer saved, but account refresh requires attention")
                }
                Result.success(Unit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { Result.failure(failure) }
        }

    fun requestLocalMutationSync(vaultId: Long) {
        BitwardenMutationSyncBridge.requestLocalMutationSync(
            context = context,
            vaultId = vaultId,
            requiresWifi = isSyncOnWifiOnly,
            autoSyncEnabled = isAutoSyncEnabled
        )
    }

    suspend fun queueCipherDelete(
        vaultId: Long,
        cipherId: String,
        entryId: Long? = null,
        itemType: String = BitwardenPendingOperation.ITEM_TYPE_PASSWORD
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            // 已有待删除操作时直接复用，保证幂等。
            val existingDelete = pendingOpDao.findActiveDeleteByCipher(vaultId, cipherId)
            if (existingDelete != null) {
                requestLocalMutationSync(vaultId)
                return@withContext Result.success(Unit)
            }

            pendingOpDao.cancelActiveRestoreByCipher(vaultId, cipherId)

            entryId?.let { id ->
                pendingOpDao.deletePendingForEntryAndType(id, itemType)
            }

            pendingOpDao.insert(
                BitwardenPendingOperation(
                    vaultId = vault.id,
                    entryId = entryId,
                    bitwardenCipherId = cipherId,
                    itemType = itemType,
                    operationType = BitwardenPendingOperation.OP_DELETE,
                    targetType = BitwardenPendingOperation.TARGET_CIPHER,
                    payloadJson = "{}",
                    status = BitwardenPendingOperation.STATUS_PENDING
                )
            )

            requestLocalMutationSync(vault.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "加入 Bitwarden 删除队列失败")
            Result.failure(e)
        }
    }

    suspend fun queueCipherRestore(
        vaultId: Long,
        cipherId: String,
        entryId: Long? = null,
        itemType: String = BitwardenPendingOperation.ITEM_TYPE_PASSWORD
    ): Result<BitwardenRestoreQueueOutcome> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            val existingRestore = pendingOpDao.findActiveRestoreByCipher(vaultId, cipherId)
            if (existingRestore != null) {
                requestLocalMutationSync(vaultId)
                return@withContext Result.success(BitwardenRestoreQueueOutcome.REMOTE_RESTORE_ALREADY_QUEUED)
            }

            val existingDelete = pendingOpDao.findActiveDeleteByCipher(vaultId, cipherId)
            if (existingDelete != null) {
                pendingOpDao.cancelActiveDeleteByCipher(vaultId, cipherId)
                requestLocalMutationSync(vaultId)
                return@withContext Result.success(BitwardenRestoreQueueOutcome.CANCELED_PENDING_DELETE)
            }

            entryId?.let { id ->
                pendingOpDao.deletePendingForEntryAndType(id, itemType)
            }

            pendingOpDao.insert(
                BitwardenPendingOperation(
                    vaultId = vault.id,
                    entryId = entryId,
                    bitwardenCipherId = cipherId,
                    itemType = itemType,
                    operationType = BitwardenPendingOperation.OP_RESTORE,
                    targetType = BitwardenPendingOperation.TARGET_CIPHER,
                    payloadJson = "{}",
                    status = BitwardenPendingOperation.STATUS_PENDING
                )
            )

            requestLocalMutationSync(vault.id)
            Result.success(BitwardenRestoreQueueOutcome.ENQUEUED_REMOTE_RESTORE)
        } catch (e: Exception) {
            Log.e(TAG, "加入 Bitwarden 恢复队列失败")
            Result.failure(e)
        }
    }

    suspend fun cancelPendingCipherDelete(vaultId: Long, cipherId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            pendingOpDao.cancelActiveDeleteByCipher(vaultId, cipherId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "取消 Bitwarden 删除队列失败")
            Result.failure(e)
        }
    }

    suspend fun deleteCipher(vaultId: Long, cipherId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_vault_missing)))

            if (!isVaultUnlocked(vaultId)) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_vault_locked)))
            }

            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_unavailable)))

            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                }
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.deleteCipher("Bearer $accessToken", cipherId)
            if (!response.isSuccessful && response.code() != 404) {
                return@withContext Result.failure(
                    IllegalStateException(strings.get(R.string.bitwarden_message_delete_http, response.code(), response.message()))
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "删除 Bitwarden Cipher 失败")
            Result.failure(e)
        }
    }

    suspend fun permanentDeleteCipher(vaultId: Long, cipherId: String): Result<Unit> = withContext(Dispatchers.IO) {
        fun failure(message: String): Result<Unit> {
            // Keep the reason in exported diagnostics without logging credentials or item contents.
            BitwardenDiagLogger.append("BitwardenRepository permanentDeleteCipher: vaultId=$vaultId, failure=$message")
            return Result.failure(IllegalStateException(message))
        }
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext failure(context.getString(takagi.ru.monica.R.string.bitwarden_trash_vault_missing))

            if (!isVaultUnlocked(vaultId)) {
                return@withContext failure(context.getString(takagi.ru.monica.R.string.bitwarden_cache_requires_unlock))
            }

            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext failure(context.getString(takagi.ru.monica.R.string.pull_sync_requires_bitwarden_login))

            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext failure(context.getString(takagi.ru.monica.R.string.pull_sync_requires_bitwarden_login))
                }
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.permanentDeleteCipher("Bearer $accessToken", cipherId)
            if (!response.isSuccessful && response.code() != 404) {
                return@withContext failure(
                    context.getString(takagi.ru.monica.R.string.bitwarden_trash_delete_http_error, response.code())
                )
            }

            BitwardenDiagLogger.append("BitwardenRepository permanentDeleteCipher: vaultId=$vaultId, status=${response.code()}")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "永久删除 Bitwarden Cipher 失败")
            BitwardenDiagLogger.append("BitwardenRepository permanentDeleteCipher: vaultId=$vaultId, exception=${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    fun observeSendsForVaults(vaultIds: List<Long>) = sendDao.getSendsByVaultsFlow(vaultIds)

    suspend fun getSends(vaultId: Long): List<BitwardenSend> = withContext(Dispatchers.IO) {
        sendDao.getSendsByVault(vaultId)
    }

    /**
     * 跨多个 Vault 拉取 Send 列表。Send 标签页用来一次展示当前所有已解锁账号下的 Send。
     */
    suspend fun getSendsForVaults(vaultIds: List<Long>): List<BitwardenSend> =
        withContext(Dispatchers.IO) {
            if (vaultIds.isEmpty()) emptyList() else sendDao.getSendsByVaults(vaultIds)
        }

    @Deprecated(
        message = "Use BitwardenRepository.syncViaCoordinator or BitwardenViewModel refreshSendsViaCoordinator so Send refresh shares the global sync queue.",
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    suspend fun refreshSends(vaultId: Long): SendSyncResult = withContext(Dispatchers.IO) {
        when (val result = sync(vaultId)) {
            is SyncResult.Success -> {
                SendSyncResult.Success(sendDao.getSendsByVault(vaultId))
            }
            is SyncResult.EmptyVaultBlocked -> {
                SendSyncResult.Warning(
                    sends = sendDao.getSendsByVault(vaultId),
                    message = result.reason
                )
            }
            is SyncResult.Error -> {
                SendSyncResult.Error(result.message)
            }
        }
    }

    private val sendJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private inner class SendSession(val vault: BitwardenVault, val key: SymmetricCryptoKey,
        val generation: Long, val lock: Long, val token: String,
        val api: takagi.ru.monica.bitwarden.api.BitwardenVaultApi) {
        fun locallyCurrent() = takagi.ru.monica.security.SessionManager.isUnlocked.value &&
            takagi.ru.monica.security.SessionManager.lockGeneration.value == lock &&
            getVaultSessionGeneration(vault.id) == generation && isVaultUnlocked(vault.id) &&
            symmetricKeyCache[vault.id] === key
        suspend fun current() {
            check(locallyCurrent())
            val actual = requireNotNull(vaultDao.getVaultById(vault.id))
            check(!actual.isLocked && BitwardenSendAccountIdentity.from(actual) == BitwardenSendAccountIdentity.from(vault))
        }
    }

    private suspend fun sendSession(vaultId: Long): SendSession {
        val vault = requireNotNull(vaultDao.getVaultById(vaultId))
        val key = requireNotNull(symmetricKeyCache[vaultId])
        val generation = getVaultSessionGeneration(vaultId)
        val lock = takagi.ru.monica.security.SessionManager.lockGeneration.value
        val token = requireNotNull(accessTokenCache[vaultId])
        val api = apiManager.protectVaultApi(vault, BitwardenApiFactory.createVaultApi(apiManager.environment(vault).api.toString(), apiManager.getOkHttpClient(vault).newBuilder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()))
        val session = SendSession(vault, key, generation, lock, token, api)
        session.current()
        if ((vault.accessTokenExpiresAt ?: 0) > System.currentTimeMillis() + 60_000L) return session
        val refreshed = requireNotNull(refreshToken(vault, publishIfCurrent = { session.current(); true }))
        session.current()
        return SendSession(vault, key, generation, lock, refreshed, api)
    }

    private fun sendRequestDocument(request: takagi.ru.monica.bitwarden.api.SendCreateRequest) =
        sendJson.encodeToJsonElement(takagi.ru.monica.bitwarden.api.SendCreateRequest.serializer(), request) as kotlinx.serialization.json.JsonObject

    private suspend fun fetchSendDocument(session: SendSession, id: String): kotlinx.serialization.json.JsonObject {
        session.current()
        val response = session.api.getSendDocument("Bearer ${session.token}", id)
        check(response.isSuccessful)
        return requireNotNull(response.body()).also {
            check(BitwardenSendEdit.string(it, "id") == id)
            session.current()
        }
    }

    private suspend fun retainSend(session: SendSession, document: kotlinx.serialization.json.JsonObject,
        dirty: Boolean): BitwardenSend {
        session.current()
        val api = sendJson.decodeFromString<takagi.ru.monica.bitwarden.api.SendApiResponse>(document.toString())
        val mapped = requireNotNull(BitwardenSendMapper.mapApiToEntity(session.vault.id, session.vault.serverUrl, api, session.key, apiManager.environment(session.vault)))
        val previous = sendDao.identity(session.vault.id, mapped.bitwardenSendId)
        val now = System.currentTimeMillis()
        val saved = mapped.copy(id = previous?.id ?: 0, createdAt = previous?.createdAt ?: now,
            updatedAt = now, lastSyncedAt = now, isDirty = dirty)
        session.current()
        val inserted = sendDao.upsert(saved)
        session.current()
        return if (saved.id == 0L) saved.copy(id = inserted) else saved
    }

    /** Deletes only the exact newly allocated request after fresh ownership/key verification. */
    private suspend fun removeUnconfirmedSend(session: SendSession, id: String, encryptedKey: String): Boolean = try {
        session.current()
        val current = session.api.getSendDocument("Bearer ${session.token}", id)
        session.current()
        if (current.code() == 404) true else {
            check(current.isSuccessful && BitwardenSendEdit.string(requireNotNull(current.body()), "key") == encryptedKey)
            val deleted = session.api.deleteSend("Bearer ${session.token}", id)
            session.current()
            deleted.isSuccessful || deleted.code() == 404
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    suspend fun createTextSend(
        vaultId: Long, title: String, text: String, notes: String?, password: String?,
        maxAccessCount: Int?, hideEmail: Boolean, hiddenText: Boolean,
        deletionMillis: Long, expirationMillis: Long?,
        authAction: SendAuthAction = SendAuthAction.KEEP, recipientEmails: List<String> = emptyList(),
        disabled: Boolean = false,
        beforeRequest: (suspend (kotlinx.serialization.json.JsonObject) -> Unit)? = null,
    ): SendMutationResult = withContext(Dispatchers.IO) {
        var started = false
        try {
            val draft = BitwardenSendDraft(title, text, notes,
                passwordAction = if (password.isNullOrEmpty()) SendPasswordAction.KEEP else SendPasswordAction.REPLACE,
                password = password, maxAccessCount = maxAccessCount, hideEmail = hideEmail,
                hiddenText = hiddenText, disabled = disabled, deletionMillis = deletionMillis,
                expirationMillis = expirationMillis, authAction = authAction, recipientEmails = recipientEmails)
            draft.validate(System.currentTimeMillis(), true)
            val desiredAuth = BitwardenSendAuth.requested(draft, true)
            require(desiredAuth != SendAuthAction.PASSWORD || !password.isNullOrEmpty())
            val session = sendSession(vaultId)
            if (desiredAuth == SendAuthAction.EMAIL && !isFileSendAllowed(session.vault))
                return@withContext SendMutationResult.Error(strings.get(R.string.bw_send_auth_premium))
            val payload = BitwardenSendMapper.buildCreateTextSendPayload(session.vault.serverUrl, session.key,
                title, text, notes, password, maxAccessCount, hideEmail, hiddenText, deletionMillis,
                expirationMillis, authAction, recipientEmails, disabled, environment = apiManager.environment(session.vault))
            val prepared = sendRequestDocument(payload.request)
            beforeRequest?.invoke(prepared)
            session.current()
            started = true
            val response = session.api.createSend("Bearer ${session.token}", payload.request)
            session.current()
            if (!response.isSuccessful) return@withContext SendMutationResult.Error(
                strings.get(if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.bitwarden_message_send_create_failed),
                uncertain = response.code() !in 400..499 || response.code() in setOf(408, 409, 425, 429))
            val body = requireNotNull(response.body())
            check(body.id.isNotBlank() && body.key == payload.request.key)
            val actual = fetchSendDocument(session, body.id)
            if (!BitwardenSendSubmission.matches(prepared, actual)) {
                val removed = removeUnconfirmedSend(session, body.id, payload.request.key)
                return@withContext SendMutationResult.Error(strings.get(
                    if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.bitwarden_message_send_create_failed), uncertain = !removed)
            }
            SendMutationResult.Success(retainSend(session, actual, dirty = true))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SendMutationResult.Error(strings.get(R.string.bitwarden_message_send_create_failed), uncertain = started) }
    }

    suspend fun createFileSend(
        vaultId: Long, title: String, fileUri: Uri?, fileName: String, notes: String?, password: String?,
        maxAccessCount: Int?, hideEmail: Boolean, deletionMillis: Long, expirationMillis: Long?,
        authAction: SendAuthAction = SendAuthAction.KEEP, recipientEmails: List<String> = emptyList(),
        disabled: Boolean = false, sourceSize: Long? = null,
        source: (suspend () -> InputStream)? = null,
        beforeRequest: (suspend (kotlinx.serialization.json.JsonObject) -> Unit)? = null,
        onUploaded: (suspend () -> Unit)? = null,
    ): SendMutationResult = withContext(Dispatchers.IO) {
        var started = false
        var session: SendSession? = null
        var createdId: String? = null
        var encryptedKey: String? = null
        var encryptedTmp: File? = null
        var sendKey: SymmetricCryptoKey? = null
        val keyMaterial = BitwardenCrypto.generateSendKeyMaterial()
        try {
            val draft = BitwardenSendDraft(title, "", notes,
                passwordAction = if (password.isNullOrEmpty()) SendPasswordAction.KEEP else SendPasswordAction.REPLACE,
                password = password, maxAccessCount = maxAccessCount, hideEmail = hideEmail, disabled = disabled,
                deletionMillis = deletionMillis, expirationMillis = expirationMillis,
                authAction = authAction, recipientEmails = recipientEmails)
            draft.validate(System.currentTimeMillis(), false)
            require(fileName.isNotBlank())
            val desiredAuth = BitwardenSendAuth.requested(draft, true)
            require(desiredAuth != SendAuthAction.PASSWORD || !password.isNullOrEmpty())
            val access = sendSession(vaultId)
            session = access
            if (!isFileSendAllowed(access.vault)) return@withContext SendMutationResult.Error(strings.get(R.string.bitwarden_message_send_premium))
            val metadata = if (source == null) AttachmentUriMetadata.resolve(context, requireNotNull(fileUri), fileName) else null
            val size = sourceSize ?: metadata?.sizeBytes ?: -1L
            require(size <= AttachmentSizeValidator.HARD_LIMIT_BYTES)
            val actualName = metadata?.fileName ?: fileName
            val cipherFile = File.createTempFile("bw_send_", ".bin", context.cacheDir)
            encryptedTmp = cipherFile
            val key = BitwardenCrypto.deriveSendKey(keyMaterial)
            sendKey = key
            val input = source?.invoke() ?: requireNotNull(context.contentResolver.openInputStream(requireNotNull(fileUri)))
            input.use { encryptSendFileData(it, cipherFile, key) { check(access.locallyCurrent()) } }
            access.current()
            require(cipherFile.length() <= AttachmentSizeValidator.HARD_LIMIT_BYTES - SEND_UPLOAD_REQUEST_HEADROOM_BYTES)
            val payload = BitwardenSendMapper.buildCreateFileSendPayload(access.key, keyMaterial, title, actualName,
                cipherFile.length(), notes, password, maxAccessCount, hideEmail, deletionMillis, expirationMillis,
                authAction, recipientEmails, disabled)
            payload.sendKey.clear()
            encryptedKey = payload.request.key
            val prepared = sendRequestDocument(payload.request)
            beforeRequest?.invoke(prepared)
            access.current()
            started = true
            val response = access.api.createFileSend("Bearer ${access.token}", payload.request)
            access.current()
            if (!response.isSuccessful) return@withContext SendMutationResult.Error(strings.get(
                if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.bitwarden_message_file_send_create_failed),
                uncertain = response.code() !in 400..499 || response.code() in setOf(408, 409, 425, 429))
            val upload = requireNotNull(response.body())
            val body = requireNotNull(upload.sendResponse)
            check(body.id.isNotBlank() && body.key == payload.request.key)
            createdId = body.id
            val beforeUpload = fetchSendDocument(access, body.id)
            if (!BitwardenSendSubmission.matches(prepared, beforeUpload)) {
                val removed = removeUnconfirmedSend(access, body.id, payload.request.key)
                return@withContext SendMutationResult.Error(strings.get(
                    if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.bitwarden_message_file_send_create_failed), uncertain = !removed)
            }
            access.current()
            uploadSendFile(access.vault, access.token, body.id, requireNotNull(body.file?.id),
                payload.encryptedFileName, cipherFile, upload.fileUploadType, upload.url)
            access.current()
            onUploaded?.invoke()
            val actual = fetchSendDocument(access, body.id)
            check(BitwardenSendSubmission.matches(prepared, actual))
            SendMutationResult.Success(retainSend(access, actual, dirty = true))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val removed = if (session != null && createdId != null && encryptedKey != null)
                removeUnconfirmedSend(session, createdId, encryptedKey) else false
            SendMutationResult.Error(strings.get(R.string.bitwarden_message_file_send_create_failed), uncertain = started && !removed)
        } finally { sendKey?.clear(); keyMaterial.fill(0); encryptedTmp?.delete() }
    }

    suspend fun reconcileSendDraft(saved: BitwardenSendDraftStore.Draft): SendMutationResult = withContext(Dispatchers.IO) {
        try {
            val session = sendSession(saved.vaultId)
            check(saved.account == BitwardenSendAccountIdentity.from(session.vault))
            val prepared = requireNotNull(saved.preparedRequest)
            check(saved.submissionUncertain && saved.submittedKey == BitwardenSendEdit.string(prepared, "key"))
            val original = saved.original
            val actual = if (original == null) {
                val response = session.api.sync("Bearer ${session.token}", excludeDomains = true)
                session.current()
                check(response.isSuccessful)
                val snapshot = requireNotNull(response.body())
                check(snapshot.profile.id == session.vault.userId &&
                    takagi.ru.monica.bitwarden.BitwardenVaultIdentity.canonicalizeEmail(snapshot.profile.email) == saved.account.canonicalEmail)
                val rows = snapshot.sends.orEmpty().map {
                    sendJson.encodeToJsonElement(takagi.ru.monica.bitwarden.api.SendApiResponse.serializer(), it) as kotlinx.serialization.json.JsonObject
                }
                val candidate = requireNotNull(BitwardenSendSubmission.uniqueCandidate(prepared, rows))
                fetchSendDocument(session, requireNotNull(BitwardenSendEdit.string(candidate, "id")))
            } else {
                check(original.vaultId == saved.vaultId)
                fetchSendDocument(session, original.bitwardenSendId)
            }
            check(BitwardenSendSubmission.matches(prepared, actual, original?.hasPassword,
                passwordRemoved = original?.hasPassword == true && saved.draft.passwordAction == SendPasswordAction.REMOVE))
            check(saved.type != BitwardenSend.TYPE_FILE || original != null || saved.fileUploadConfirmed)
            val mapped = requireNotNull(BitwardenSendMapper.mapApiToEntity(session.vault.id, session.vault.serverUrl,
                sendJson.decodeFromString<takagi.ru.monica.bitwarden.api.SendApiResponse>(actual.toString()), session.key,
                apiManager.environment(session.vault)))
            check(if (original != null && BitwardenSendAuth.requested(saved.draft, false) == SendAuthAction.KEEP)
                mapped.hasPassword == original.hasPassword else BitwardenSendAuth.matches(mapped, saved.draft, original))
            session.current()
            SendMutationResult.Success(retainSend(session, actual, dirty = original == null))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SendMutationResult.Error(strings.get(R.string.bw_send_draft_not_found), uncertain = true) }
    }


    private fun isFileSendAllowed(vault: BitwardenVault): Boolean {
        // 与官方 Bitwarden 客户端一致：文件 Send 需要 Premium 账户。
        // Premium 状态来自 sync 响应的 profile.premium || profile.premiumFromOrganization。
        // Vaultwarden 默认对所有用户返回 premium=true，所以自建服务器天然允许。
        return BitwardenVaultPremiumStore.isPremium(context, vault.id)
    }

    private fun resolveSendUploadUrl(vault: BitwardenVault, uploadUrl: String?): String {
        val raw = uploadUrl?.takeIf { it.isNotBlank() }
            ?: throw IOException(strings.get(R.string.bitwarden_message_upload_url_missing))
        check(raw.none { it.isWhitespace() || it.code < 32 || it == '\\' } && !raw.startsWith("//")) {
            "Bitwarden upload destination rejected"
        }
        val apiBase = apiManager.environment(vault).api
        // Keep the raw path spelling until the transport policy chooses the client.
        val absolute = when {
            raw.toHttpUrlOrNull() != null -> raw
            raw.startsWith('/') -> "${apiBase.scheme}://${apiBase.toUri().rawAuthority}$raw"
            else -> apiBase.toString() + raw
        }
        check(BitwardenTransportPolicy.validateTransferUrl(apiManager.environment(vault), absolute).isValid) {
            "Bitwarden upload destination rejected"
        }
        return absolute
    }

    private fun encryptSendFileData(
        source: InputStream,
        target: File,
        sendKey: SymmetricCryptoKey,
        checkCurrent: () -> Unit = {}
    ) {
        val macOffset = 1L + 16L
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(sendKey.encKey, "AES"),
                IvParameterSpec(iv)
            )
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(sendKey.macKey, "HmacSHA256"))
            update(iv)
        }

        target.outputStream().buffered().use { output ->
            output.write(byteArrayOf(BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC.toByte()))
            output.write(iv)
            output.write(ByteArray(32))

            val buffer = ByteArray(16 * 1024)
            var consumed = 0L
            while (true) {
                checkCurrent()
                val read = source.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                consumed += read
                check(consumed <= AttachmentSizeValidator.HARD_LIMIT_BYTES)
                val encryptedChunk = cipher.update(buffer, 0, read)
                if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                    output.write(encryptedChunk)
                    mac.update(encryptedChunk)
                }
            }

            buffer.fill(0)
            checkCurrent()
            val finalChunk = cipher.doFinal()
            if (finalChunk.isNotEmpty()) {
                output.write(finalChunk)
                mac.update(finalChunk)
            }
            output.flush()
        }

        RandomAccessFile(target, "rw").use { file ->
            file.seek(macOffset)
            file.write(mac.doFinal())
        }
    }

    private fun formatBytes(sizeBytes: Long): String {
        if (sizeBytes < 0) return "unknown"
        if (sizeBytes < 1024) return "$sizeBytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = sizeBytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(java.util.Locale.US, "%.2f %s", value, units[unitIndex])
    }

    private suspend fun uploadSendFile(
        vault: BitwardenVault,
        accessToken: String,
        sendId: String,
        fileId: String,
        encryptedFileName: String,
        encryptedFile: File,
        fileUploadType: Int,
        uploadUrl: String?
    ) = withContext(Dispatchers.IO) {
        val key = requireNotNull(symmetricKeyCache[vault.id])
        val requireCurrent = attachmentGuard(vault, key, accessToken)
        requireCurrent()
        if (fileUploadType == FILE_UPLOAD_TYPE_DIRECT) {
            val dataBody = encryptedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val dataPart = MultipartBody.Part.createFormData("data", encryptedFileName, dataBody)
            val response = apiManager.getVaultApi(vault).uploadSendFileDirect(
                authorization = "Bearer $accessToken",
                sendId = sendId,
                fileId = fileId,
                data = dataPart
            )
            requireCurrent()
            if (!response.isSuccessful) {
                val message = if (response.code() == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
                    strings.get(R.string.bitwarden_message_upload_limit_http, formatBytes(encryptedFile.length()))
                } else {
                    strings.get(R.string.bitwarden_message_upload_code, response.code())
                }
                throw IOException(message)
            }
        } else {
            val url = resolveSendUploadUrl(vault, uploadUrl)
            val httpClient = apiManager.getTransferClient(vault, url)
            val request = Request.Builder()
                .url(url)
                .put(encryptedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .header("x-ms-blob-type", "BlockBlob")
                .build()
            httpClient.newCall(request).execute().use { response ->
                requireCurrent()
                if (response.code != HttpURLConnection.HTTP_CREATED && !response.isSuccessful) {
                    val message = if (response.code == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
                        strings.get(R.string.bitwarden_message_upload_limit)
                    } else {
                        strings.get(R.string.bitwarden_message_upload_code, response.code)
                    }
                    throw IOException(message)
                }
            }
        }
    }

    suspend fun updateSend(existing: BitwardenSend, draft: BitwardenSendDraft,
        beforeRequest: (suspend (kotlinx.serialization.json.JsonObject) -> Unit)? = null): SendMutationResult =
        withContext(Dispatchers.IO) {
            syncMutexForVault(existing.vaultId).withLock {
                var attempted = false
                try {
                    draft.validate(System.currentTimeMillis(), existing.isTextType)
                    val session = sendSession(existing.vaultId)
                    val desiredAuth = BitwardenSendAuth.requested(draft, creating = false)
                    if (desiredAuth == SendAuthAction.EMAIL) {
                        if (existing.hasPassword) return@withLock SendMutationResult.Error(strings.get(R.string.send_edit_unsupported_auth))
                        if (!isFileSendAllowed(session.vault)) return@withLock SendMutationResult.Error(strings.get(R.string.bw_send_auth_premium))
                    }
                    fun map(document: kotlinx.serialization.json.JsonObject): BitwardenSend {
                        val api = sendJson.decodeFromString<takagi.ru.monica.bitwarden.api.SendApiResponse>(document.toString())
                        return requireNotNull(BitwardenSendMapper.mapApiToEntity(session.vault.id, session.vault.serverUrl, api, session.key, apiManager.environment(session.vault)))
                    }
                    val baseline = fetchSendDocument(session, existing.bitwardenSendId)
                    val remote = map(baseline)
                    if (remote.revisionDate != existing.revisionDate || remote.keyBase64 != existing.keyBase64 ||
                        remote.type != existing.type || remote.accessId != existing.accessId) {
                        return@withLock SendMutationResult.Error(strings.get(R.string.send_edit_conflict))
                    }
                    val request = BitwardenSendMapper.buildUpdatePayload(baseline, draft, session.key)
                    if (!BitwardenSendEdit.sameBaseline(baseline, fetchSendDocument(session, existing.bitwardenSendId))) {
                        return@withLock SendMutationResult.Error(strings.get(R.string.send_edit_conflict))
                    }
                    beforeRequest?.invoke(request)
                    session.current()
                    attempted = true
                    try {
                        val response = session.api.updateSendDocument("Bearer ${session.token}", existing.bitwardenSendId, request)
                        session.current()
                        if (!response.isSuccessful) return@withLock SendMutationResult.Error(strings.get(
                            if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.send_edit_failed),
                            uncertain = response.code() !in 400..499 || response.code() in setOf(408, 409, 425, 429))
                    } catch (_: IOException) { /* One readback can confirm a committed write with a lost response. */ }
                    var actual = fetchSendDocument(session, existing.bitwardenSendId)
                    var updated = map(actual)
                    fun settingsMatch(value: BitwardenSend) = value.name == draft.title && value.notes == draft.notes.orEmpty() &&
                        (!value.isTextType || (value.textContent == draft.text && value.isTextHidden == draft.hiddenText)) &&
                        value.disabled == draft.disabled && value.hideEmail == draft.hideEmail &&
                        value.maxAccessCount == draft.maxAccessCount &&
                        value.deletionDate?.let { Instant.parse(it).toEpochMilli() } == draft.deletionMillis &&
                        value.expirationDate?.let { Instant.parse(it).toEpochMilli() } == draft.expirationMillis &&
                        value.keyBase64 == existing.keyBase64 && value.type == existing.type && value.accessId == existing.accessId &&
                        BitwardenSendEdit.field(actual, "file") == BitwardenSendEdit.field(baseline, "file")
                    if (!settingsMatch(updated)) return@withLock SendMutationResult.Error(strings.get(R.string.send_edit_failed), uncertain = true)
                    if (draft.passwordAction == SendPasswordAction.REMOVE && updated.hasPassword) {
                        session.current()
                        try {
                            val response = session.api.removeSendPassword("Bearer ${session.token}", existing.bitwardenSendId)
                            session.current()
                            if (!response.isSuccessful) return@withLock SendMutationResult.Error(strings.get(R.string.send_edit_failed), uncertain = true)
                        } catch (_: IOException) { /* Read the potentially committed removal once. */ }
                        actual = fetchSendDocument(session, existing.bitwardenSendId)
                        updated = map(actual)
                    }
                    if (!settingsMatch(updated) || !BitwardenSendAuth.matches(updated, draft, remote)) {
                        return@withLock SendMutationResult.Error(strings.get(
                            if (desiredAuth == SendAuthAction.EMAIL) R.string.bw_send_auth_unsupported else R.string.send_edit_failed), uncertain = true)
                    }
                    session.current()
                    SendMutationResult.Success(retainSend(session, actual, dirty = false))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SendMutationResult.Error(strings.get(R.string.send_edit_failed), uncertain = attempted) }
            }
        }

    suspend fun deleteSend(vaultId: Long, sendId: String): SendMutationResult = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId)
                ?: return@withContext SendMutationResult.Error(strings.get(R.string.bitwarden_message_vault_missing))
            if (!isVaultUnlocked(vaultId)) {
                return@withContext SendMutationResult.Error(strings.get(R.string.legacy_ui_vault_locked))
            }

            var accessToken = accessTokenCache[vaultId]
                ?: return@withContext SendMutationResult.Error(strings.get(R.string.bitwarden_message_token_unavailable))
            val expiresAt = vault.accessTokenExpiresAt ?: 0
            if (expiresAt <= System.currentTimeMillis() + 60000) {
                val refreshed = refreshToken(vault)
                if (refreshed != null) {
                    accessToken = refreshed
                } else {
                    return@withContext SendMutationResult.Error(strings.get(R.string.bitwarden_message_token_refresh_failed))
                }
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.deleteSend(
                authorization = "Bearer $accessToken",
                sendId = sendId
            )

            if (!response.isSuccessful && response.code() != 404) {
                return@withContext SendMutationResult.Error(
                    strings.get(R.string.bitwarden_message_send_delete_http, response.code(), response.message())
                )
            }

            sendDao.deleteBySendId(vaultId, sendId)
            SendMutationResult.Deleted(sendId)
        } catch (e: Exception) {
            Log.e(TAG, "删除 Send 失败")
            SendMutationResult.Error(e.message ?: strings.get(R.string.bitwarden_message_send_delete_failed))
        }
    }
    
    suspend fun getPasswordEntriesByFolder(vaultId: Long, folderId: String): List<PasswordEntry> = withContext(Dispatchers.IO) {
        passwordEntryDao.getByBitwardenFolderId(vaultId, folderId)
    }
    
    suspend fun searchEntries(vaultId: Long, query: String): List<PasswordEntry> = withContext(Dispatchers.IO) {
        passwordEntryDao.searchBitwardenEntries(vaultId, query)
    }
    
    suspend fun getConflictBackups(vaultId: Long): List<BitwardenConflictBackup> = withContext(Dispatchers.IO) {
        conflictDao.getUnresolvedConflictsByVault(vaultId)
    }
    
    /**
     * 解决冲突：使用本地版本
     * 标记冲突为已解决，保留当前本地数据
     */
    suspend fun resolveConflictWithLocal(conflictId: Long): Boolean =
        resolveCipherConflict(conflictId, keepLocal = true)

    suspend fun resolveConflictWithServer(conflictId: Long): Boolean =
        resolveCipherConflict(conflictId, keepLocal = false)

    /** A resolution authorizes only the server revision the user was shown. */
    private suspend fun resolveCipherConflict(conflictId: Long, keepLocal: Boolean): Boolean = withContext(Dispatchers.IO) {
        val conflict = conflictDao.getById(conflictId) ?: return@withContext false
        syncMutexForVault(conflict.vaultId).withLock {
            try {
                if (conflict.isResolved || !isVaultUnlocked(conflict.vaultId)) return@withLock false
                val vault = vaultDao.getVaultById(conflict.vaultId) ?: return@withLock false
                val token = accessTokenCache[vault.id] ?: return@withLock false
                val key = symmetricKeyCache[vault.id] ?: return@withLock false
                val profileGeneration = getVaultSessionGeneration(vault.id)
                val profileAppGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
                val cipherId = conflict.bitwardenCipherId ?: return@withLock false
                val api = apiManager.getVaultApi(vault)
                val profileResponse = api.profile("Bearer $token")
                val profile = profileResponse.body() ?: return@withLock false
                if (!profileResponse.isSuccessful || !validateBitwardenSessionProfile(vault.id, token, profile) {
                    profileGeneration == getVaultSessionGeneration(vault.id) && symmetricKeyCache[vault.id] === key &&
                        profileAppGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value
                }) return@withLock false
                val response = api.getCipher("Bearer $token", cipherId)
                val remote = response.body() ?: return@withLock false
                if (!response.isSuccessful || remote.id != cipherId ||
                    !takagi.ru.monica.bitwarden.service.BitwardenCipherRevisionGuard.matches(conflict.serverRevisionDate, remote.revisionDate)) {
                    return@withLock false
                }
                if (keepLocal) takagi.ru.monica.bitwarden.service.BitwardenCipherOwnership.requireWritable(remote, cipherId)
                val ownerKey = if (remote.organizationId != null)
                    takagi.ru.monica.bitwarden.service.BitwardenCipherKeyResolver.resolveRemoteCipherKey(api, token, remote.copy(key = null), key)
                else null
                try {
                    database.withTransaction {
                        val currentConflict = conflictDao.getById(conflictId) ?: error("Conflict removed")
                        check(currentConflict == conflict && isAccessTokenCurrent(vault.id, token) && isVaultUnlocked(vault.id))
                        val password = passwordEntryDao.getByBitwardenCipherIdInVault(vault.id, cipherId)
                        val secure = secureItemDao.getByBitwardenCipherIdInVault(vault.id, cipherId)
                        check(password != null || secure != null) { "Local item unavailable" }
                        // Force a full remote projection when choosing the server. The transaction
                        // rolls back the dirty flags if decoding or key validation fails.
                        password?.let {
                            passwordEntryDao.update(it.copy(
                                bitwardenRevisionDate = if (keepLocal) remote.revisionDate else null,
                                bitwardenLocalModified = keepLocal
                            ))
                        }
                        secure?.let {
                            secureItemDao.update(it.copy(
                                bitwardenRevisionDate = if (keepLocal) remote.revisionDate else null,
                                bitwardenLocalModified = keepLocal,
                                syncStatus = if (keepLocal) "PENDING" else "SYNCED"
                            ))
                        }
                        if (!keepLocal) {
                            val result = takagi.ru.monica.bitwarden.service.CipherSyncProcessor(context).syncCipherFromServer(
                                vault, remote, key,
                                organizationKeys = remote.organizationId?.let { mapOf(it to requireNotNull(ownerKey)) }.orEmpty()
                            )
                            check(result is takagi.ru.monica.bitwarden.service.CipherSyncResult.Added ||
                                result is takagi.ru.monica.bitwarden.service.CipherSyncResult.Updated) {
                                "Server snapshot could not be restored"
                            }
                        }
                        conflictDao.markResolved(conflictId, if (keepLocal)
                            BitwardenConflictBackup.RESOLUTION_KEEP_LOCAL else BitwardenConflictBackup.RESOLUTION_KEEP_SERVER)
                    }
                    true
                } finally { ownerKey?.clear() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Conflict resolution retained both versions: ${error.javaClass.simpleName}")
                false
            }
        }
    }

    // ==================== 设置 ====================
    
    var isAutoSyncEnabled: Boolean
        get() = (securePrefs?.getBoolean(KEY_AUTO_SYNC_ENABLED, true) ?: false)
        set(value) { securePrefs?.edit()?.putBoolean(KEY_AUTO_SYNC_ENABLED, value)?.apply() }
    
    var isSyncOnWifiOnly: Boolean
        get() = (securePrefs?.getBoolean(KEY_SYNC_ON_WIFI_ONLY, false) ?: true)
        set(value) { securePrefs?.edit()?.putBoolean(KEY_SYNC_ON_WIFI_ONLY, value)?.apply() }
    
    /**
     * 是否永不锁定 Bitwarden
     * 
     * 开启后：
     * - Bitwarden Vault 将保持解锁状态
     * - 密钥会持久化保存在内存中
     * - 适合安全环境下使用
     */
    var isNeverLockEnabled: Boolean
        get() = (securePrefs?.getBoolean(KEY_NEVER_LOCK_BITWARDEN, false) ?: false)
        set(value) { securePrefs?.edit()?.putBoolean(KEY_NEVER_LOCK_BITWARDEN, value)?.apply() }
    
    val lastSyncTime: Long
        get() = (securePrefs?.getLong(KEY_LAST_SYNC_TIME, 0) ?: 0)

    /**
     * 同步队列计数（实时）
     */
    fun getPendingSyncCountFlow(): Flow<Int> = pendingOpDao.getPendingCountFlow()

    fun getFailedSyncCountFlow(): Flow<Int> = pendingOpDao.getFailedCountFlow()
    
    // ==================== 加密辅助 ====================
    
    private fun encryptForStorage(data: String): String {
        return Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    
    private fun decryptFromStorage(data: String): String {
        return String(Base64.decode(data, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private suspend fun restoreUnlockStateFromVault(vault: BitwardenVault): Boolean {
        if (isAccountMutationBlocked(vault.id) || vault.isLocked || !takagi.ru.monica.security.SessionManager.isUnlocked.value) return false
        val appGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
        val epoch = BitwardenAuthSessionGate.epoch(authenticationScope(vault.serverUrl, vault.email))
        val storedEncKey = vault.encryptedEncKey?.let(::decryptFromStorage) ?: return false
        val storedMacKey = vault.encryptedMacKey?.let(::decryptFromStorage) ?: return false
        val symmetricKey = SymmetricCryptoKey(Base64.decode(storedEncKey, Base64.NO_WRAP), Base64.decode(storedMacKey, Base64.NO_WRAP))
        return publishRecoveredVault(vault, symmetricKey, vault.encryptedAccessToken?.let(::decryptFromStorage), appGeneration, epoch)
    }

    private suspend fun publishRecoveredVault(vault: BitwardenVault, key: SymmetricCryptoKey, token: String?,
        appGeneration: Long, epoch: Long): Boolean {
        var transferred = false
        try {
            return BitwardenAuthSessionGate.publish(authenticationScope(vault.serverUrl, vault.email), epoch) {
                val caller = currentCoroutineContext()
                takagi.ru.monica.security.SessionManager.publishAtGeneration(appGeneration) {
                    caller.ensureActive()
                    database.runInTransaction {
                        val latest = vaultDao.getVaultForTransportPublication(vault.id)
                        check(latest == vault) { "Bitwarden account changed during unlock" }
                        database.attachmentDao().resetRetiredBitwardenDownloads(vault.id)
                        vaultDao.publishTransport(vault.copy(isLocked = false, updatedAt = System.currentTimeMillis()))
                        caller.ensureActive()
                    }
                    symmetricKeyCache[vault.id] = key
                    if (!isAccountMutationBlocked(vault.id)) token?.let { accessTokenCache[vault.id] = it }
                    else accessTokenCache.remove(vault.id)
                    transferred = true
                    true
                } == true
            } == true
        } finally { if (!transferred) key.clear() }
    }

    private suspend fun getAvailableOfflineSecretCount(vaultId: Long): Int {
        return passwordEntryDao.getBitwardenEntriesCount(vaultId)
    }

    private fun syncMutexForVault(vaultId: Long): Mutex {
        val existing = vaultSyncMutexes[vaultId]
        if (existing != null) {
            return existing
        }

        val created = Mutex()
        val raced = vaultSyncMutexes.putIfAbsent(vaultId, created)
        return raced ?: created
    }

    private fun clearVaultSyncMutex(vaultId: Long) {
        vaultSyncMutexes.remove(vaultId)
    }
    
    // ==================== 结果类型 ====================

    sealed class SendSyncResult {
        data class Success(val sends: List<BitwardenSend>) : SendSyncResult()
        data class Warning(val sends: List<BitwardenSend>, val message: String) : SendSyncResult()
        data class Error(val message: String) : SendSyncResult()
    }

    sealed class SendMutationResult {
        data class Success(val send: BitwardenSend) : SendMutationResult()
        data class Deleted(val sendId: String) : SendMutationResult()
        data class Error(val message: String, val uncertain: Boolean = false) : SendMutationResult()
    }
    
    sealed class RepositoryLoginResult {
        data class Success(val vault: BitwardenVault) : RepositoryLoginResult()
        data class TwoFactorRequired(
            val providers: List<Int>,
            val state: LoginResult.TwoFactorRequired
        ) : RepositoryLoginResult()
        data class CaptchaRequired(
            val message: String,
            val siteKey: String? = null
        ) : RepositoryLoginResult()
        data class Error(val message: String) : RepositoryLoginResult()
    }
    
    sealed class UnlockResult {
        object Success : UnlockResult()
        data class Error(val message: String) : UnlockResult()
    }
    
    sealed class SyncResult {
        data class Success(
            val appliedChangeCount: Int,
            val remoteAddedCount: Int,
            val remoteUpdatedCount: Int,
            val uploadedCount: Int,
            val deletedCount: Int,
            val availableOfflineCount: Int,
            val conflictCount: Int,
            val uploadFailedCount: Int,
            val skippedDueToLocalDirtyCount: Int
        ) : SyncResult()
        data class Error(val message: String) : SyncResult()
        
        /**
         * 空 Vault 保护阻止了同步
         */
        data class EmptyVaultBlocked(
            val vaultId: Long,
            val localCount: Int,
            val serverCount: Int,
            val reason: String
        ) : SyncResult()
    }

    enum class CacheClearMode {
        SAFE_ONLY_SYNCED,
        FULL_FORCE
    }

    data class VaultCacheRiskSummary(
        val vaultId: Long,
        val pendingOperationCount: Int,
        val passwordLocalModifiedCount: Int,
        val secureItemLocalModifiedCount: Int,
        val unresolvedConflictCount: Int
    ) {
        val hasRisk: Boolean
            get() = pendingOperationCount > 0 ||
                passwordLocalModifiedCount > 0 ||
                secureItemLocalModifiedCount > 0 ||
                unresolvedConflictCount > 0
    }

    data class CacheClearResult(
        val mode: CacheClearMode,
        val riskSummary: VaultCacheRiskSummary,
        val protectedCipherCount: Int,
        val passwordClearedCount: Int,
        val secureItemClearedCount: Int,
        val passkeyClearedCount: Int,
        val folderClearedCount: Int,
        val sendClearedCount: Int,
        val unresolvedConflictClearedCount: Int,
        val pendingOperationClearedCount: Int
    ) {
        val totalClearedCount: Int
            get() = passwordClearedCount +
                secureItemClearedCount +
                passkeyClearedCount +
                folderClearedCount +
                sendClearedCount +
                unresolvedConflictClearedCount +
                pendingOperationClearedCount
    }
}

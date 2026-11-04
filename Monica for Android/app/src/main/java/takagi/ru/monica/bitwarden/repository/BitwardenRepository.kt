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
                
                // 默认返回原始错误（截断过长内容）
                else -> {
                    val shortError = if (rawError.length > 100) {
                        rawError.take(100) + "..."
                    } else {
                        rawError
                    }
                    strings.get(R.string.bitwarden_message_login_failed, shortError)
                }
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
    private val apiManager = BitwardenApiManager()
    private val authService = BitwardenAuthService(context)
    private val syncService = BitwardenSyncService(context, sessionProfileValidator = ::validateBitwardenSessionProfile,
        sessionIsActive = { vaultId, key -> isVaultUnlocked(vaultId) && symmetricKeyCache[vaultId] === key })
    private val historicalTotpRepairService = BitwardenHistoricalTotpRepairService(context)
    
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
                        symmetricKeyCache[vaultId] === key && accessTokenCache[vaultId] == token) { "Export account session changed" }
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
                            accessTokenCache[vaultId] == oldToken &&
                            vaultDao.getVaultById(vaultId)?.let { takagi.ru.monica.bitwarden.export.bitwardenJsonAccountFingerprint(it) == fingerprint } == true
                    })) { "Cannot refresh export account" }
                    check(accessTokenCache.replace(vaultId, oldToken, token))
                }
                checkAccount()
                val response = apiManager.getVaultApi(vault).syncSource("Bearer $token")
                check(response.isSuccessful) { "Cannot read complete export source (${response.code()})" }
                val document = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.read(requireNotNull(response.body()), sync = true)
                val sync = takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.decodeSync(document)
                currentCoroutineContext().ensureActive()
                check(validateBitwardenSessionProfile(vaultId, token, sync.profile)) { "Export account security state changed" }
                checkAccount()
                val result = takagi.ru.monica.bitwarden.export.BitwardenJsonSourceExporter.fromSync(sync, key, ::current)
                checkAccount()
                result
            }
        }

    private val syncIssues = takagi.ru.monica.bitwarden.service.BitwardenSyncIssues()
    val syncIssueReports = syncIssues.reports
    private val vaultSessionGenerations = ConcurrentHashMap<Long, kotlinx.coroutines.flow.MutableStateFlow<Long>>()

    fun getVaultSessionGenerationFlow(vaultId: Long): kotlinx.coroutines.flow.StateFlow<Long> =
        vaultSessionGenerations.getOrPut(vaultId) { kotlinx.coroutines.flow.MutableStateFlow(0L) }

    fun getVaultSessionGeneration(vaultId: Long): Long = getVaultSessionGenerationFlow(vaultId).value

    private fun advanceVaultSession(vaultId: Long) {
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
            return super.put(key, value).also { advanceVaultSession(key) }
        }
        override fun remove(key: Long): SymmetricCryptoKey? {
            advanceVaultSession(key)
            return super.remove(key).also { advanceVaultSession(key) }
        }
        override fun clear() {
            val ids = keys.toList()
            ids.forEach(::advanceVaultSession)
            super.clear()
            ids.forEach(::advanceVaultSession)
            syncIssues.clearAll()
        }
    }
    private val accessTokenCache = ConcurrentHashMap<Long, String>()

    private val rememberedTwoFactorStore by lazy {
        BitwardenRememberedTwoFactorStore(
            readSealed = { key -> runCatching { securePrefs?.getString(key, null) }.getOrNull() },
            writeSealed = { key, value -> runCatching { securePrefs?.edit()?.putString(key, value)?.commit() == true }.getOrDefault(false) },
            removeSealed = { key -> runCatching { securePrefs?.edit()?.remove(key)?.commit() == true }.getOrDefault(false) },
        )
    }

    private fun authenticationScope(serverUrl: String, email: String): String =
        BitwardenRememberedTwoFactorStore.scope(
            BitwardenVaultIdentity.normalizeServerUrl(serverUrl),
            BitwardenVaultIdentity.canonicalizeEmail(email),
        )

    /** Called only with a successful profile response, before any writes or sync projection. */
    private suspend fun validateBitwardenSessionProfile(
        vaultId: Long,
        expectedAccessToken: String,
        profile: ProfileResponse,
    ): Boolean {
        val vault = vaultDao.getVaultById(vaultId) ?: return false
        val scope = authenticationScope(vault.serverUrl, vault.email)
        val decision = BitwardenAuthSessionGate.inspect(scope) {
            val current = vaultDao.getVaultById(vaultId) ?: return@inspect null
            if (accessTokenCache[vaultId] != expectedAccessToken || !isVaultUnlocked(vaultId)) return@inspect null
            if (!current.userId.isNullOrBlank() && current.userId != profile.id) {
                return@inspect BitwardenStampDecision.CHANGED
            }
            val comparison = BitwardenSecurityStampPolicy.compare(current.revisionDate, profile.securityStamp)
            if (comparison == BitwardenStampDecision.FIRST_OBSERVATION) {
                vaultDao.update(current.copy(revisionDate = profile.securityStamp))
            }
            comparison
        }
        if (decision == BitwardenStampDecision.CHANGED) {
            invalidateBitwardenSession(vaultId, expectedAccessToken)
        }
        return decision == BitwardenStampDecision.FIRST_OBSERVATION || decision == BitwardenStampDecision.MATCH
    }

    /** Stamp revocation preserves records/queue and bypasses the optional never-lock preference. */
    private suspend fun invalidateBitwardenSession(vaultId: Long, expectedAccessToken: String): Boolean {
        val vault = vaultDao.getVaultById(vaultId) ?: return false
        val scope = authenticationScope(vault.serverUrl, vault.email)
        return BitwardenAuthSessionGate.revokeIf(scope,
            predicate = { accessTokenCache[vaultId] == expectedAccessToken && isVaultUnlocked(vaultId) },
            action = {
                symmetricKeyCache.remove(vaultId)?.clear()
                accessTokenCache.remove(vaultId)
                rememberedTwoFactorStore.clear(scope)
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
            val url = takagi.ru.monica.bitwarden.sync.BitwardenNotificationProtocol.hubUrl(vault.serverUrl)
                ?: return@withLock null
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
                token = refreshToken(vault, publishIfCurrent = { current() && accessTokenCache[vaultId] == previous })
                    ?: return@withLock null
                currentCoroutineContext().ensureActive()
                if (!current() || !accessTokenCache.replace(vaultId, previous, token)) return@withLock null
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
                val refreshed = refreshToken(vault, publishIfCurrent = { current() && accessTokenCache[vaultId] == previous })
                    ?: return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                if (!current() || !accessTokenCache.replace(vaultId, previous, refreshed)) {
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
            if (!current() || !validateBitwardenSessionProfile(vaultId, token, profile)) {
                return@withContext Result.failure(IllegalStateException(strings.get(R.string.legacy_ui_reauthenticate)))
            }
            val response = api.getAccountDevices("Bearer $token")
            if (!response.isSuccessful) return@withContext Result.failure(IllegalStateException(
                strings.get(R.string.bitwarden_account_request_failed, response.code())))
            currentCoroutineContext().ensureActive()
            if (accessTokenCache[vaultId] != token || !current()) {
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
    
    // ==================== Vault 管理 ====================
    
    /**
     * 获取所有 Vault
     */
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
        securePrefs?.edit()?.putLong(KEY_ACTIVE_VAULT_ID, vaultId)?.apply()
    }
    
    /**
     * 获取 Vault 的解锁状态
     */
    fun isVaultUnlocked(vaultId: Long): Boolean {
        return symmetricKeyCache.containsKey(vaultId) && accessTokenCache.containsKey(vaultId)
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
        if (cipherId.isNullOrBlank()) return null
        val accessToken = accessTokenCache[vault.id] ?: return null
        val wrappingKey = symmetricKeyCache[vault.id] ?: return null
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
                    val currentToken = accessTokenCache[vault.id] ?: throw takagi.ru.monica.attachments.model.AttachmentError.BitwardenLocked
                    val currentKey = symmetricKeyCache[vault.id] ?: throw takagi.ru.monica.attachments.model.AttachmentError.BitwardenLocked
                    takagi.ru.monica.bitwarden.service.BitwardenAttachmentKeyResolver.resolve(
                        vaultApi, currentToken, cipherId, currentKey
                    )
                }
            }
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
                    isOnline = true
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
        tlsConfig: BitwardenTlsConfig? = null
    ): RepositoryLoginResult = withContext(Dispatchers.IO) {
        try {
            val normalizedEmail = email.trim()
            Log.d(TAG, "开始登录 Bitwarden: $normalizedEmail")
            
            val effectiveServerUrl = serverUrl?.takeIf { it.isNotBlank() } 
                ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
            
            val scope = authenticationScope(effectiveServerUrl, normalizedEmail)
            val loginEpoch = BitwardenAuthSessionGate.begin(scope)
            val rememberedToken = rememberedTwoFactorStore.read(scope)
            var loginResult = authService.login(
                email = normalizedEmail,
                password = masterPassword,
                serverUrl = effectiveServerUrl,
                captchaResponse = captchaResponse,
                tlsConfig = tlsConfig,
                rememberedTwoFactorToken = rememberedToken
            )

            // A rejected remembered token must not strand the account. Network/KDF failures
            // retain it; a server/account rejection retries the ordinary password flow once.
            val error = loginResult.exceptionOrNull()
            if (rememberedToken != null && error != null &&
                error !is CancellationException && error !is BitwardenKdfMemoryException &&
                classifyBitwardenLoginError(error) == null) {
                rememberedTwoFactorStore.clear(scope)
                loginResult = authService.login(normalizedEmail, masterPassword, effectiveServerUrl,
                    captchaResponse = captchaResponse, tlsConfig = tlsConfig)
            }
            
            loginResult.fold(
                onSuccess = { result ->
                    when (result) {
                        is LoginResult.Success -> {
                            handleSuccessfulLogin(result, normalizedEmail, tlsConfig, loginEpoch)
                        }
                        is LoginResult.TwoFactorRequired -> {
                            rememberedTwoFactorStore.clear(scope)
                            RepositoryLoginResult.TwoFactorRequired(
                                providers = result.providers,
                                state = result.copy(authSessionEpoch = loginEpoch)
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
                    Log.e(TAG, "登录失败", error)
                    RepositoryLoginResult.Error(describeLoginError(error))
                }
            )
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "登录 KDF 内存不足", e)
            RepositoryLoginResult.Error(describeLoginError(e))
        } catch (e: Exception) {
            Log.e(TAG, "登录异常", e)
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
            val effectiveServerUrl = serverUrl?.takeIf { it.isNotBlank() }
                ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
            val scope = authenticationScope(effectiveServerUrl, twoFactorState.email)
            val loginEpoch = twoFactorState.authSessionEpoch ?: BitwardenAuthSessionGate.begin(scope)
            
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
                            loginEpoch, rememberTwoFactor = remember && twoFactorProvider != BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE)
                        is LoginResult.CaptchaRequired -> RepositoryLoginResult.CaptchaRequired(
                            message = result.message,
                            siteKey = result.siteKey
                        )
                        is LoginResult.TwoFactorRequired -> RepositoryLoginResult.TwoFactorRequired(
                            providers = result.providers,
                            state = result.copy(authSessionEpoch = loginEpoch)
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "两步验证登录失败", error)
                    RepositoryLoginResult.Error(describeLoginError(error))
                }
            )
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "两步验证 KDF 内存不足", e)
            RepositoryLoginResult.Error(describeLoginError(e))
        } catch (e: Exception) {
            Log.e(TAG, "两步验证异常", e)
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
        val effectiveServerUrl = serverUrl?.takeIf { it.isNotBlank() }
            ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
        authService.sendTwoFactorEmailLogin(
            twoFactorState = twoFactorState,
            serverUrl = effectiveServerUrl,
            tlsConfig = tlsConfig
        )
    }
    
    /**
     * 处理成功登录
     */
    private suspend fun handleSuccessfulLogin(
        result: LoginResult.Success,
        email: String,
        tlsConfig: BitwardenTlsConfig?,
        loginEpoch: Long,
        rememberTwoFactor: Boolean? = null
    ): RepositoryLoginResult {
        currentCoroutineContext().ensureActive()
        val scope = authenticationScope(result.serverUrls.vault, email)
        return BitwardenAuthSessionGate.publish(scope, loginEpoch) {
            currentCoroutineContext().ensureActive()
            val published = handleSuccessfulLoginCurrent(result, email, tlsConfig)
            if (published is RepositoryLoginResult.Success) {
                if (rememberTwoFactor != null) {
                    rememberedTwoFactorStore.save(scope, rememberTwoFactor, result.twoFactorToken)
                } else if (result.twoFactorToken != null && rememberedTwoFactorStore.read(scope) != null) {
                    rememberedTwoFactorStore.save(scope, true, result.twoFactorToken)
                }
                result.masterKey.fill(0)
                result.stretchedKey.clear()
            }
            published
        } ?: run {
            result.clearKeys()
            RepositoryLoginResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
        }
    }

    private suspend fun handleSuccessfulLoginCurrent(
        result: LoginResult.Success,
        email: String,
        tlsConfig: BitwardenTlsConfig?
    ): RepositoryLoginResult {
        val displayEmail = email.trim()
        val canonicalEmail = BitwardenVaultIdentity.canonicalizeEmail(displayEmail)
        val accountKey = BitwardenVaultIdentity.buildAccountKey(
            serverUrl = result.serverUrls.vault,
            userId = null,
            canonicalEmail = canonicalEmail
        )
        // 加密敏感数据用于存储
        val encryptedAccessToken = encryptForStorage(result.accessToken)
        val encryptedRefreshToken = result.refreshToken?.let { encryptForStorage(it) }
        
        // 加密密钥
        val encryptedMasterKey = encryptForStorage(Base64.encodeToString(result.masterKey, Base64.NO_WRAP))
        val encryptedEncKey = encryptForStorage(Base64.encodeToString(result.symmetricKey.encKey, Base64.NO_WRAP))
        val encryptedMacKey = encryptForStorage(Base64.encodeToString(result.symmetricKey.macKey, Base64.NO_WRAP))
        
        // 查找或创建 Vault
        val existingVault = vaultDao.getVaultByAccountKey(accountKey)
            ?: vaultDao.getVaultByServerAndCanonicalEmail(
                serverUrl = result.serverUrls.vault,
                canonicalEmail = canonicalEmail
            )
        val expiresAt = System.currentTimeMillis() + (result.expiresIn * 1000L)
        
        val vault = if (existingVault != null) {
            existingVault.copy(
                email = displayEmail,
                canonicalEmail = canonicalEmail,
                accountKey = accountKey,
                serverUrl = result.serverUrls.vault,
                identityUrl = result.serverUrls.identity,
                apiUrl = result.serverUrls.api,
                tlsCertificateAlias = tlsConfig?.certificateAlias ?: existingVault.tlsCertificateAlias,
                tlsCaCertificatePem = tlsConfig?.caCertificatePem ?: existingVault.tlsCaCertificatePem,
                tlsMtlsEnabled = tlsConfig?.mtlsEnabled ?: existingVault.tlsMtlsEnabled,
                tlsClientCertPkcs12Base64 = tlsConfig?.clientCertPkcs12Base64
                    ?: existingVault.tlsClientCertPkcs12Base64,
                tlsEncryptedClientCertPassword = tlsConfig?.clientCertPassword
                    ?: existingVault.tlsEncryptedClientCertPassword,
                encryptedAccessToken = encryptedAccessToken,
                encryptedRefreshToken = encryptedRefreshToken,
                accessTokenExpiresAt = expiresAt,
                encryptedMasterKey = encryptedMasterKey,
                encryptedEncKey = encryptedEncKey,
                encryptedMacKey = encryptedMacKey,
                kdfType = result.kdfType,
                kdfIterations = result.kdfIterations,
                kdfMemory = result.kdfMemory,
                kdfParallelism = result.kdfParallelism,
                revisionDate = null,
                isLocked = false,
                isConnected = true,
                updatedAt = System.currentTimeMillis()
            ).also { vaultDao.update(it) }
        } else {
            BitwardenVault(
                email = displayEmail,
                canonicalEmail = canonicalEmail,
                accountKey = accountKey,
                serverUrl = result.serverUrls.vault,
                identityUrl = result.serverUrls.identity,
                apiUrl = result.serverUrls.api,
                tlsCertificateAlias = tlsConfig?.certificateAlias,
                tlsCaCertificatePem = tlsConfig?.caCertificatePem,
                tlsMtlsEnabled = tlsConfig?.mtlsEnabled == true,
                tlsClientCertPkcs12Base64 = tlsConfig?.clientCertPkcs12Base64,
                tlsEncryptedClientCertPassword = tlsConfig?.clientCertPassword,
                encryptedAccessToken = encryptedAccessToken,
                encryptedRefreshToken = encryptedRefreshToken,
                accessTokenExpiresAt = expiresAt,
                encryptedMasterKey = encryptedMasterKey,
                encryptedEncKey = encryptedEncKey,
                encryptedMacKey = encryptedMacKey,
                kdfType = result.kdfType,
                kdfIterations = result.kdfIterations,
                kdfMemory = result.kdfMemory,
                kdfParallelism = result.kdfParallelism,
                isLocked = false,
                isConnected = true,
                isDefault = vaultDao.getVaultCount() == 0
            ).let { newVault ->
                val id = vaultDao.insert(newVault)
                newVault.copy(id = id)
            }
        }
        
        // 缓存密钥和令牌
        symmetricKeyCache[vault.id] = result.symmetricKey
        accessTokenCache[vault.id] = result.accessToken
        
        // 设置为活跃 Vault
        setActiveVault(vault.id)
        
        Log.d(TAG, "登录成功: vaultId=${vault.id}")
        return RepositoryLoginResult.Success(vault)
    }
    
    /**
     * 解锁已登录的 Vault
     */
    suspend fun unlock(vaultId: Long, masterPassword: String): UnlockResult = withContext(Dispatchers.IO) {
        try {
            val vault = vaultDao.getVaultById(vaultId) ?: return@withContext UnlockResult.Error(strings.get(R.string.bitwarden_message_vault_missing))
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
                        
                        // 缓存密钥
                        symmetricKeyCache[vaultId] = symmetricKey
                        
                        // 尝试恢复访问令牌
                        vault.encryptedAccessToken?.let {
                            accessTokenCache[vaultId] = decryptFromStorage(it)
                        }
                        
                        // 更新状态
                        vaultDao.setLocked(vaultId, false)
                        
                        return@withContext UnlockResult.Success
                    } catch (e: Exception) {
                        Log.e(TAG, "密钥恢复失败，尝试重新登录", e)
                    }
                }
                
                // 密钥恢复失败，需要重新登录
                UnlockResult.Error(strings.get(R.string.legacy_ui_reauthenticate))
            } finally {
                masterKey.fill(0)
            }
        } catch (e: BitwardenKdfMemoryException) {
            Log.e(TAG, "解锁 KDF 内存不足", e)
            UnlockResult.Error(strings.get(R.string.bitwarden_message_kdf_memory))
        } catch (e: Exception) {
            Log.e(TAG, "解锁异常", e)
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
        symmetricKeyCache.remove(vaultId)
        accessTokenCache.remove(vaultId)
        vaultDao.setLocked(vaultId, true)
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
            Log.e(TAG, "恢复解锁状态失败", e)
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
        symmetricKeyCache.remove(vaultId)
        accessTokenCache.remove(vaultId)
        vaultDao.setLocked(vaultId, true)
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
        symmetricKeyCache.clear()
        accessTokenCache.clear()
        getAllVaults().forEach { vault ->
            vaultDao.setLocked(vault.id, true)
        }
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
            rememberedTwoFactorStore.clear(scope)
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

            // 重置活跃 Vault
            if ((securePrefs?.getLong(KEY_ACTIVE_VAULT_ID, -1) ?: -1) == vaultId) {
                securePrefs?.edit()?.remove(KEY_ACTIVE_VAULT_ID)?.apply()
            }
            
            Log.d(TAG, "Vault 已登出: $vaultId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "登出失败", e)
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
                        accessTokenCache[vaultId] = accessToken
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
                if (!validateBitwardenSessionProfile(vaultId, accessToken, profile)) {
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
                Log.e(TAG, "同步异常", e)
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
                        accessTokenCache[vaultId] = refreshed
                    } else {
                        return@withContext Result.failure(IllegalStateException(strings.get(R.string.bitwarden_message_token_refresh_failed)))
                    }
                }

                Result.success(
                    historicalTotpRepairService.repairHistoricalTotp(
                        vault = vault,
                        accessToken = accessToken,
                        symmetricKey = symmetricKey
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "修复历史 Bitwarden TOTP 失败", e)
                Result.failure(e)
            }
        }
    
    /**
     * 刷新访问令牌
     */
    private suspend fun refreshToken(
        vault: BitwardenVault,
        publishIfCurrent: (suspend () -> Boolean)? = null,
    ): String? {
        val refreshToken = vault.encryptedRefreshToken?.let { decryptFromStorage(it) } ?: return null
        
        return try {
            val result = authService.refreshToken(
                refreshToken = refreshToken,
                identityUrl = vault.identityUrl,
                refererUrl = vault.serverUrl,
                tlsConfig = BitwardenTlsConfig(
                    certificateAlias = vault.tlsCertificateAlias,
                    caCertificatePem = vault.tlsCaCertificatePem,
                    mtlsEnabled = vault.tlsMtlsEnabled,
                    clientCertPkcs12Base64 = vault.tlsClientCertPkcs12Base64,
                    clientCertPassword = vault.tlsEncryptedClientCertPassword
                )
            )
            result.getOrNull()?.let { refreshResult ->
                // 更新存储
                val encryptedAccessToken = encryptForStorage(refreshResult.accessToken)
                val encryptedRefreshToken = refreshResult.refreshToken?.let { encryptForStorage(it) }
                val expiresAt = System.currentTimeMillis() + (refreshResult.expiresIn * 1000L)
                
                suspend fun publish(): String {
                    vaultDao.updateAccessToken(vault.id, encryptedAccessToken, expiresAt)
                    encryptedRefreshToken?.let { vaultDao.updateRefreshToken(vault.id, it) }
                    return refreshResult.accessToken
                }
                if (publishIfCurrent == null) publish() else {
                    BitwardenAuthSessionGate.inspect(authenticationScope(vault.serverUrl, vault.email)) {
                        currentCoroutineContext().ensureActive()
                        if (publishIfCurrent()) publish() else null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Token 刷新失败", e)
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "创建 Bitwarden 文件夹失败", e)
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "重命名 Bitwarden 文件夹失败", e)
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "删除 Bitwarden 文件夹失败", e)
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
                        accessTokenCache[vaultId] = token
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
            Log.e(TAG, "加入 Bitwarden 删除队列失败", e)
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
            Log.e(TAG, "加入 Bitwarden 恢复队列失败", e)
            Result.failure(e)
        }
    }

    suspend fun cancelPendingCipherDelete(vaultId: Long, cipherId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            pendingOpDao.cancelActiveDeleteByCipher(vaultId, cipherId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "取消 Bitwarden 删除队列失败", e)
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "删除 Bitwarden Cipher 失败", e)
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "永久删除 Bitwarden Cipher 失败", e)
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
        val api = BitwardenApiFactory.createVaultApi(vault.apiUrl, apiManager.getOkHttpClient(vault).newBuilder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build())
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
        val mapped = requireNotNull(BitwardenSendMapper.mapApiToEntity(session.vault.id, session.vault.serverUrl, api, session.key))
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
                expirationMillis, authAction, recipientEmails, disabled)
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
                sendJson.decodeFromString<takagi.ru.monica.bitwarden.api.SendApiResponse>(actual.toString()), session.key))
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
        val trimmed = uploadUrl?.trim().takeIf { !it.isNullOrBlank() }
            ?: throw IOException(strings.get(R.string.bitwarden_message_upload_url_missing))
        trimmed.toHttpUrlOrNull()?.let { return trimmed }

        val apiBase = vault.apiUrl
            .takeIf { it.isNotBlank() }
            ?: BitwardenApiFactory.inferServerUrls(vault.serverUrl).api
        val resolved = apiBase.toHttpUrlOrNull()?.resolve(trimmed)
        return resolved?.toString()
            ?: throw IOException(strings.get(R.string.bitwarden_message_upload_url_invalid, trimmed))
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
        if (fileUploadType == FILE_UPLOAD_TYPE_DIRECT) {
            val dataBody = encryptedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val dataPart = MultipartBody.Part.createFormData("data", encryptedFileName, dataBody)
            val response = apiManager.getVaultApi(vault).uploadSendFileDirect(
                authorization = "Bearer $accessToken",
                sendId = sendId,
                fileId = fileId,
                data = dataPart
            )
            if (!response.isSuccessful) {
                val message = if (response.code() == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
                    strings.get(R.string.bitwarden_message_upload_limit_http, formatBytes(encryptedFile.length()))
                } else {
                    strings.get(R.string.bitwarden_message_upload_http, response.code(), response.message())
                }
                throw IOException(message)
            }
        } else {
            val url = resolveSendUploadUrl(vault, uploadUrl)
            val tlsConfig = BitwardenTlsConfig(
                certificateAlias = vault.tlsCertificateAlias,
                caCertificatePem = vault.tlsCaCertificatePem,
                mtlsEnabled = vault.tlsMtlsEnabled,
                clientCertPkcs12Base64 = vault.tlsClientCertPkcs12Base64,
                clientCertPassword = vault.tlsEncryptedClientCertPassword
            )
            val httpClient: OkHttpClient = BitwardenApiFactory.createOkHttpClient(
                refererUrl = vault.serverUrl,
                tlsConfig = tlsConfig
            )
            val request = Request.Builder()
                .url(url)
                .put(encryptedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .header("x-ms-blob-type", "BlockBlob")
                .build()
            httpClient.newCall(request).execute().use { response ->
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
                        return requireNotNull(BitwardenSendMapper.mapApiToEntity(session.vault.id, session.vault.serverUrl, api, session.key))
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
                    accessTokenCache[vaultId] = refreshed
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
            Log.e(TAG, "删除 Send 失败", e)
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
                val cipherId = conflict.bitwardenCipherId ?: return@withLock false
                val api = apiManager.getVaultApi(vault)
                val profileResponse = api.profile("Bearer $token")
                val profile = profileResponse.body() ?: return@withLock false
                if (!profileResponse.isSuccessful || !validateBitwardenSessionProfile(vault.id, token, profile)) return@withLock false
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
                        check(currentConflict == conflict && accessTokenCache[vault.id] == token && isVaultUnlocked(vault.id))
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
        if (vault.isLocked) {
            return false
        }

        val storedEncKey = vault.encryptedEncKey?.let { decryptFromStorage(it) }
        val storedMacKey = vault.encryptedMacKey?.let { decryptFromStorage(it) }

        if (storedEncKey.isNullOrBlank() || storedMacKey.isNullOrBlank()) {
            return false
        }

        val encKeyBytes = Base64.decode(storedEncKey, Base64.NO_WRAP)
        val macKeyBytes = Base64.decode(storedMacKey, Base64.NO_WRAP)
        val symmetricKey = SymmetricCryptoKey(encKeyBytes, macKeyBytes)

        symmetricKeyCache[vault.id] = symmetricKey
        vault.encryptedAccessToken
            ?.let { decryptFromStorage(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { restoredAccessToken ->
                accessTokenCache[vault.id] = restoredAccessToken
            }

        vaultDao.setLocked(vault.id, false)
        Log.d(TAG, "成功恢复 Vault 解锁状态: ${vault.id}")
        return true
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

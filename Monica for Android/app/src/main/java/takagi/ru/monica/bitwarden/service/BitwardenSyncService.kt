package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.sync.EmptyVaultProtection
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.LOGIN_TYPE_STEAM_MAFILE
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.PasswordWebsiteCodec
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.steam.data.SteamExternalMaFileContract
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/**
 * Bitwarden 同步服务
 * 
 * 负责:
 * 1. 全量同步 - 首次登录或强制刷新
 * 2. 增量同步 - 基于 revision date 的差异同步
 * 3. 冲突检测和处理
 * 4. 离线操作队列管理
 * 
 * 安全规则:
 * - 同步失败时保留本地数据，不删除
 * - 冲突时优先保留本地修改，并备份服务器版本
 * - 所有数据库操作使用事务
 */
class BitwardenSyncService(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    
    companion object {
        private const val TAG = "BitwardenSyncService"
        private val CIPHER_STRING_PATTERN =
            Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")
    }
    
    private val strings = AppLocaleStringResolver(context)
    private val database = PasswordDatabase.getDatabase(context)
    private val vaultDao = database.bitwardenVaultDao()
    private val folderDao = database.bitwardenFolderDao()
    private val sendDao = database.bitwardenSendDao()
    private val conflictDao = database.bitwardenConflictBackupDao()
    private val pendingOpDao = database.bitwardenPendingOperationDao()
    private val passwordEntryDao = database.passwordEntryDao()
    private val customFieldDao = database.customFieldDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val securityManager = SecurityManager(context)
    private val passwordCustomFieldSyncState = BitwardenPasswordCustomFieldSyncState(context)
    
    // 多类型 Cipher 同步处理器
    private val cipherSyncProcessor = CipherSyncProcessor(context)
    private val cipherUploadProcessor = CipherUploadProcessor(context, apiManager)

    // 附件元数据对齐器（只对齐元数据 + 清理本地孤儿缓存，不下载字节）
    private val attachmentReconciler = AttachmentContainer.bitwardenReconciler(context)
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        runCatching { OperationLogger.init(context.applicationContext) }
        runCatching { BitwardenDiagLogger.initialize(context.applicationContext) }
    }

    private data class ParsedLoginUris(
        val website: String = "",
        val appPackageName: String = ""
    )

    private fun normalizeWebsiteKey(rawWebsite: String): String {
        return PasswordWebsiteCodec.normalizeForKey(normalizeBitwardenWebsite(rawWebsite))
    }
    
    /**
     * 执行全量同步
     * 
     * @param vault Vault 配置
     * @param accessToken 访问令牌
     * @param symmetricKey 对称加密密钥
     */
    suspend fun fullSync(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): SyncResult = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "Starting full sync for vault ${vault.id}")
        
        try {
                val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.sync(
                authorization = "Bearer $accessToken"
            )
            
            if (!response.isSuccessful) {
                val errorPayload = runCatching { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "sync_full",
                    method = "GET",
                    endpoint = "/sync?excludeDomains=true",
                    requestBody = null,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "sync failed: ${response.code()} ${response.message()}"
                )
                return@withContext SyncResult.Error(
                    "Sync failed: ${response.code()} ${response.message()}"
                )
            }
            
            val syncResponse = response.body()
            if (syncResponse == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "sync_full",
                    method = "GET",
                    endpoint = "/sync?excludeDomains=true",
                    requestBody = null,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "empty sync response"
                )
                return@withContext SyncResult.Error("Empty sync response")
            }

            val rawForensicsEnabled = runCatching {
                BitwardenSyncForensicsLogger.isRawCaptureEnabled(context)
            }.getOrDefault(false)
            if (rawForensicsEnabled) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "sync_full",
                    method = "GET",
                    endpoint = "/sync?excludeDomains=true",
                    requestBody = null,
                    responseCode = response.code(),
                    responseBody = buildSyncFullRawSummary(syncResponse),
                    success = true
                )
                runCatching {
                    BitwardenSyncForensicsLogger.captureSyncCipherSnapshots(
                        context = context,
                        vaultId = vault.id,
                        ciphers = syncResponse.ciphers
                    )
                }.onFailure { captureError ->
                    android.util.Log.w(TAG, "Capture sync cipher snapshots failed: ${captureError.message}")
                }
            }
            
            // ===== 空 Vault 保护检查 =====
            val serverCipherCount = syncResponse.ciphers.size
            val localCipherCount = passwordEntryDao.getBitwardenEntriesCount(vault.id)
            val isFirstSync = vault.lastSyncAt == null
            
            val protectionResult = EmptyVaultProtection.checkSyncAllowed(
                strings = strings,
                vaultId = vault.id,
                localCipherCount = localCipherCount,
                serverCipherCount = serverCipherCount,
                isFirstSync = isFirstSync
            )
            
            when (protectionResult) {
                is EmptyVaultProtection.CheckResult.Blocked -> {
                    android.util.Log.w(TAG, "⚠️ 空 Vault 保护触发: ${protectionResult.reason}")
                    // 发送警告事件
                    EmptyVaultProtection.emitEmptyVaultDetected(
                        vaultId = vault.id,
                        localCount = protectionResult.localCount,
                        serverCount = protectionResult.serverCount
                    )
                    return@withContext SyncResult.EmptyVaultBlocked(
                        localCount = protectionResult.localCount,
                        serverCount = protectionResult.serverCount,
                        reason = protectionResult.reason
                    )
                }
                is EmptyVaultProtection.CheckResult.FirstSyncAllowed -> {
                    android.util.Log.i(TAG, "首次同步，允许空 Vault")
                }
                is EmptyVaultProtection.CheckResult.Allowed -> {
                    android.util.Log.d(TAG, "同步检查通过")
                }
            }
            
            // 额外检查：是否有显著数据丢失风险
            if (EmptyVaultProtection.checkSignificantDataLoss(localCipherCount, serverCipherCount)) {
                android.util.Log.w(TAG, "⚠️ 检测到潜在数据丢失: 本地 $localCipherCount 条 → 服务器 $serverCipherCount 条")
                // 这里不阻止同步，但记录警告
            }
            // ===== 空 Vault 保护检查结束 =====
            
            // 处理同步数据
            val result = processSyncResponse(vault, syncResponse, symmetricKey)
            
            // 更新 Vault 同步状态
            val now = System.currentTimeMillis()
            val profileIdentity = BitwardenVaultIdentity.createProfileIdentity(
                serverUrl = vault.serverUrl,
                profile = syncResponse.profile,
                fallbackEmail = vault.email
            )
            vaultDao.updateIdentityAndSyncStatus(
                vaultId = vault.id,
                email = profileIdentity.email,
                canonicalEmail = profileIdentity.canonicalEmail,
                userId = profileIdentity.userId,
                accountKey = profileIdentity.accountKey,
                displayName = profileIdentity.displayName,
                lastSyncAt = now,
                revisionDate = syncResponse.profile.securityStamp
            )
            // 记录 profile.premium 供附件 UI 判断（批量移动弹窗 / 上传按钮启用态）
            BitwardenVaultPremiumStore.setPremium(
                context = context,
                vaultId = vault.id,
                premium = syncResponse.profile.hasPremium
            )
            
            android.util.Log.i(TAG, "Full sync completed: $result")
            result
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Full sync failed", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }
    
    /**
     * 处理同步响应数据
     */
    private suspend fun processSyncResponse(
        vault: BitwardenVault,
        response: SyncResponse,
        symmetricKey: SymmetricCryptoKey
    ): SyncResult {
        // Send 防回收的双重保护基线：本次 sync 启动时刻。
        // 任何 created_at >= 这个值的 send，都会被 deleteNotInProtectingDirty 视作"本次 sync 之后才出现"。
        val syncStartedAtMs = System.currentTimeMillis()
        val forensicsSession = BitwardenSyncForensicsLogger.startSession(
            context = context,
            vaultId = vault.id,
            response = response
        )
        var foldersAdded = 0
        var ciphersAdded = 0
        var ciphersUpdated = 0
        var conflictsDetected = 0
        var skippedDueToLocalDirty = 0
        var sendsSynced = 0
        val forensicsCollector: ((BitwardenSyncForensicsSummary) -> Unit)? =
            if (forensicsSession.enabled) {
                { summary -> BitwardenSyncForensicsLogger.recordCipher(forensicsSession, summary) }
            } else {
                null
            }
        val activeServerCipherIds = response.ciphers
            .asSequence()
            .filter { it.deletedDate == null }
            .map { it.id }
            .toList()

        try {
            // 1. 同步文件夹
            response.folders.forEach { folderApi ->
                try {
                    val existingFolder = folderDao.getFolderByBitwardenId(folderApi.id)
                    if (existingFolder != null &&
                        existingFolder.revisionDate == folderApi.revisionDate &&
                        existingFolder.encryptedName == folderApi.name
                    ) {
                        return@forEach
                    }

                    val decryptedName = decryptFolderName(folderApi.name, symmetricKey)

                    if (existingFolder == null) {
                        // 新文件夹
                        folderDao.upsert(
                            BitwardenFolder(
                                vaultId = vault.id,
                                bitwardenFolderId = folderApi.id,
                                name = decryptedName,
                                encryptedName = folderApi.name,
                                revisionDate = folderApi.revisionDate
                            )
                        )
                        foldersAdded++
                    } else {
                        // 更新文件夹
                        folderDao.update(
                            existingFolder.copy(
                                name = decryptedName,
                                encryptedName = folderApi.name,
                                revisionDate = folderApi.revisionDate,
                                lastSyncedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to sync folder ${folderApi.id}: ${e.message}")
                }
            }

            // 2. 同步 Ciphers (使用新的多类型处理器)
            response.ciphers.forEach { cipherApi ->
                try {
                    // 使用 CipherSyncProcessor 处理所有类型
                    val result = cipherSyncProcessor.syncCipherFromServer(
                        vault = vault,
                        cipher = cipherApi,
                        symmetricKey = symmetricKey,
                        forensicsCollector = forensicsCollector
                    )
                    when (result) {
                        is CipherSyncResult.Added -> ciphersAdded++
                        is CipherSyncResult.Updated -> ciphersUpdated++
                        is CipherSyncResult.Conflict -> conflictsDetected++
                        is CipherSyncResult.Skipped -> {
                            if (result.reason == "Local changes pending upload") {
                                skippedDueToLocalDirty++
                            }
                        }
                        is CipherSyncResult.Error -> {
                            android.util.Log.w(TAG, "Cipher sync error: ${result.message}")
                        }
                    }
                    // 附件元数据对齐：Bitwarden 的附件属于 Cipher，可对应密码或安全项。
                    val remoteUnchanged =
                        result is CipherSyncResult.Skipped &&
                            result.reason == CipherSyncProcessor.SKIP_REMOTE_UNCHANGED
                    val shouldReconcileAttachments =
                        cipherApi.attachments != null &&
                            (!remoteUnchanged || cipherApi.attachments.isNotEmpty())
                    if (shouldReconcileAttachments) {
                        runCatching {
                            val localPassword = passwordEntryDao.getByBitwardenCipherIdInVault(
                                vaultId = vault.id,
                                cipherId = cipherApi.id
                            )
                            val owner = localPassword?.let { AttachmentOwner.password(it.id) }
                                ?: secureItemDao.getByBitwardenCipherIdInVault(
                                    vaultId = vault.id,
                                    cipherId = cipherApi.id
                                )?.let { AttachmentOwner.secureItem(it.id) }
                            if (owner != null) {
                                BitwardenCipherKeyResolver.withCipherKey(
                                    cipher = cipherApi,
                                    vaultKey = symmetricKey,
                                    logTag = TAG
                                ) { effectiveKey ->
                                    attachmentReconciler.reconcile(
                                        owner = owner,
                                        remoteAttachments = BitwardenAttachmentMetadataDecoder
                                            .decodeForStorage(cipherApi.attachments.orEmpty(), effectiveKey)
                                    )
                                }
                            }
                        }.onFailure { err ->
                            android.util.Log.w(TAG, "Attachment reconcile failed for ${cipherApi.id}: ${err.message}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to sync cipher ${cipherApi.id}: ${e.message}")
                    BitwardenSyncForensicsLogger.recordCipher(
                        forensicsSession,
                        BitwardenSyncForensicsSummary(
                            cipherId = cipherApi.id,
                            cipherType = cipherApi.type,
                            syncOutcome = "ERROR",
                            deleted = cipherApi.deletedDate != null,
                            revisionMillis = parseRevisionMillis(cipherApi.revisionDate),
                            customFieldCount = cipherApi.fields?.size ?: 0,
                            message = e.message?.take(200)
                        )
                    )
                }
            }

            // 2.1 清理服务器已不存在的本地 Cipher（delete-wins）
            if (activeServerCipherIds.isEmpty()) {
                passwordEntryDao.deleteAllSyncedBitwardenEntries(vault.id)
                secureItemDao.deleteAllSyncedBitwardenEntries(vault.id)
                passkeyDao.deleteAllByBitwardenVaultId(vault.id)
            } else {
                passwordEntryDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
                secureItemDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
                passkeyDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
            }

            // 3. 清理已删除的文件夹 (服务器上不存在的)
            val serverFolderIds = response.folders.map { it.id }
            folderDao.deleteNotIn(vault.id, serverFolderIds)

            // 4. 同步 Sends
            response.sends?.forEach { sendApi ->
                try {
                    val synced = syncSend(vault, sendApi, symmetricKey)
                    if (synced) {
                        sendsSynced++
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to sync send ${sendApi.id}: ${e.message}")
                }
            }
            response.sends?.let { sends ->
                val serverSendIds = sends.map { it.id }
                // 双重保护：保留 is_dirty=1 行（写后读不一致）+ 本次 sync 之后落地的行（兜底）
                if (serverSendIds.isEmpty()) {
                    sendDao.deleteByVaultProtectingDirty(vault.id, syncStartedAtMs)
                } else {
                    sendDao.deleteNotInProtectingDirty(vault.id, serverSendIds, syncStartedAtMs)
                }
            }

            if (sendsSynced > 0) {
                android.util.Log.i(TAG, "Sends synced: $sendsSynced")
            }

            val successResult = SyncResult.Success(
                foldersAdded = foldersAdded,
                ciphersAdded = ciphersAdded,
                ciphersUpdated = ciphersUpdated,
                conflictsDetected = conflictsDetected,
                skippedDueToLocalDirty = skippedDueToLocalDirty
            )
            BitwardenSyncForensicsLogger.finishSession(context, forensicsSession, successResult)
            return successResult
        } catch (e: Exception) {
            BitwardenSyncForensicsLogger.finishSession(
                context,
                forensicsSession,
                SyncResult.Error("processSyncResponse failed: ${e.message ?: "unknown"}")
            )
            throw e
        }
    }

    private suspend fun syncSend(
        vault: BitwardenVault,
        sendApi: SendApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val mapped = BitwardenSendMapper.mapApiToEntity(
            vaultId = vault.id,
            serverUrl = vault.serverUrl,
            api = sendApi,
            vaultKey = symmetricKey
        ) ?: return false

        val existing = sendDao.getBySendId(vault.id, mapped.bitwardenSendId)
        val now = System.currentTimeMillis()
        val entity = if (existing == null) {
            mapped.copy(
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                // 服务器返回了这条 Send，说明已对账完成
                isDirty = false
            )
        } else {
            mapped.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                updatedAt = now,
                lastSyncedAt = now,
                // 一旦服务器确认这条 send，本地 dirty 标记失效
                isDirty = false
            )
        }
        sendDao.upsert(entity)
        return true
    }
    
    /**
     * 同步单个 Cipher
     */
    private suspend fun syncCipher(
        vault: BitwardenVault,
        cipherApi: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): CipherSyncResult {
        // 只处理 Login 类型的 Cipher (类型 1)
        if (cipherApi.type != 1) {
            return CipherSyncResult.Skipped("Only login ciphers are supported")
        }
        
        // 跳过已删除的 Cipher
        if (cipherApi.deletedDate != null) {
            return CipherSyncResult.Skipped("Cipher is deleted")
        }
        
        // 查找本地是否存在此 Cipher
        val existingEntry = passwordEntryDao.getByBitwardenCipherIdInVault(vault.id, cipherApi.id)
        
        if (existingEntry == null) {
            // 新建条目
            val newEntry = cipherToPasswordEntry(vault, cipherApi, symmetricKey)
            if (newEntry != null) {
                passwordEntryDao.insert(newEntry)
                return CipherSyncResult.Added
            } else {
                return CipherSyncResult.Error("Failed to convert cipher")
            }
        } else {
            // 检查是否有本地修改
            if (existingEntry.bitwardenLocalModified) {
                // 检测冲突
                if (existingEntry.bitwardenRevisionDate != cipherApi.revisionDate) {
                    // 版本冲突 - 创建备份
                    createConflictBackup(
                        vault = vault,
                        entry = existingEntry,
                        serverCipher = cipherApi,
                        conflictType = BitwardenConflictBackup.TYPE_CONCURRENT_EDIT
                    )
                    return CipherSyncResult.Conflict
                }
            }
            
            // 更新条目
            val updatedEntry = updatePasswordEntryFromCipher(
                existingEntry, vault.id, cipherApi, symmetricKey
            )
            if (updatedEntry != null) {
                passwordEntryDao.update(updatedEntry)
                return CipherSyncResult.Updated
            } else {
                return CipherSyncResult.Error("Failed to update entry")
            }
        }
    }
    
    /**
     * 将 Cipher 转换为 PasswordEntry
     */
    private fun cipherToPasswordEntry(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            
            // 解密字段
            val name = decryptString(cipher.name, symmetricKey) ?: "Untitled"
            val username = decryptString(login.username, symmetricKey) ?: ""
            val decryptedPassword = decryptString(login.password, symmetricKey)
            if (!login.password.isNullOrBlank() && decryptedPassword == null) {
                android.util.Log.w(TAG, "Skip cipher ${cipher.id}: password decrypt failed")
                return null
            }
            val password = decryptedPassword ?: ""
            val notes = decryptString(cipher.notes, symmetricKey) ?: ""
            val totp = decryptString(login.totp, symmetricKey) ?: ""
            val parsedUris = parseLoginUris(login.uris, symmetricKey)
            val customFields = parsePasswordCustomFieldMap(cipher.fields, symmetricKey)
            val isSteamMaFileEntry = SteamExternalMaFileContract.isMarked(
                customFields.entries.map { it.key to it.value }
            )
            val encryptedPassword = encryptBitwardenPasswordForOfflineDisplay(password, cipher.id)
            val sshKeyData = buildSshKeyDataFromCustomFields(customFields)
            
            return PasswordEntry(
                title = name,
                website = parsedUris.website,
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = encodeBitwardenTotpForLocalStorage(totp),
                appPackageName = customFields["monica_app_package"]
                    ?: customFields["appPackageName"]
                    ?: parsedUris.appPackageName,
                appName = customFields["monica_app_name"]
                    ?: customFields["appName"]
                    ?: "",
                email = customFields["monica_email"]
                    ?: customFields["email"]
                    ?: "",
                phone = customFields["monica_phone"]
                    ?: customFields["phone"]
                    ?: "",
                addressLine = customFields["monica_address_line"]
                    ?: customFields["addressLine"]
                    ?: customFields["address"]
                    ?: "",
                city = customFields["monica_city"] ?: customFields["city"] ?: "",
                state = customFields["monica_state"] ?: customFields["state"] ?: "",
                zipCode = customFields["monica_zip_code"]
                    ?: customFields["zipCode"]
                    ?: "",
                country = customFields["monica_country"] ?: customFields["country"] ?: "",
                passkeyBindings = customFields["monica_passkey_bindings"].orEmpty(),
                sshKeyData = sshKeyData,
                loginType = when {
                    takagi.ru.monica.data.model.ApiKeyEntryFields.isApiKey(customFields) -> takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE
                    takagi.ru.monica.data.model.GpgEntryFields.isGpg(customFields) -> "GPG_KEY"
                    isSteamMaFileEntry -> LOGIN_TYPE_STEAM_MAFILE
                    sshKeyData.isNotBlank() -> LOGIN_TYPE_SSH_KEY
                    else -> "PASSWORD"
                },
                isFavorite = cipher.favorite,
                createdAt = Date(),
                updatedAt = Date(),
                // Bitwarden 关联字段
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenCipherType = cipher.type,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to convert cipher: ${e.message}")
            return null
        }
    }
    
    /**
     * 从 Cipher 更新 PasswordEntry
     */
    private fun updatePasswordEntryFromCipher(
        entry: PasswordEntry,
        vaultId: Long,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            
            val name = decryptString(cipher.name, symmetricKey) ?: entry.title
            val username = decryptString(login.username, symmetricKey) ?: entry.username
            val decryptedPassword = decryptString(login.password, symmetricKey)
            val encryptedPassword = decryptedPassword?.let {
                encryptBitwardenPasswordForOfflineDisplay(it, cipher.id)
            } ?: entry.password
            val notes = decryptString(cipher.notes, symmetricKey) ?: entry.notes
            val remoteTotp = decryptString(login.totp, symmetricKey)
            val storedTotp = remoteTotp?.let(::encodeBitwardenTotpForLocalStorage) ?: entry.authenticatorKey
            val parsedUris = parseLoginUris(login.uris, symmetricKey)
            val customFields = parsePasswordCustomFieldMap(cipher.fields, symmetricKey)
            val isSteamMaFileEntry = SteamExternalMaFileContract.isMarked(
                customFields.entries.map { it.key to it.value }
            )
            val remoteAppPackage = customFields["monica_app_package"]
                ?: customFields["appPackageName"]
                ?: parsedUris.appPackageName
            val remoteAppName = customFields["monica_app_name"]
                ?: customFields["appName"]
                ?: ""
            val remoteEmail = customFields["monica_email"]
                ?: customFields["email"]
                ?: ""
            val remotePhone = customFields["monica_phone"]
                ?: customFields["phone"]
                ?: ""
            val remoteAddress = customFields["monica_address_line"]
                ?: customFields["addressLine"]
                ?: customFields["address"]
                ?: ""
            val remoteCity = customFields["monica_city"] ?: customFields["city"] ?: ""
            val remoteState = customFields["monica_state"] ?: customFields["state"] ?: ""
            val remoteZip = customFields["monica_zip_code"]
                ?: customFields["zipCode"]
                ?: ""
            val remoteCountry = customFields["monica_country"] ?: customFields["country"] ?: ""
            val remotePasskeyBindings = customFields["monica_passkey_bindings"].orEmpty()
            val remoteSshKeyData = buildSshKeyDataFromCustomFields(customFields)
            
            return entry.copy(
                title = name,
                website = if (entry.isApiKeyEntry() || takagi.ru.monica.data.model.ApiKeyEntryFields.isApiKey(customFields))
                    parsedUris.website else parsedUris.website.ifBlank { entry.website },
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = storedTotp,
                appPackageName = remoteAppPackage.ifBlank { entry.appPackageName },
                appName = remoteAppName.ifBlank { entry.appName },
                email = remoteEmail.ifBlank { entry.email },
                phone = remotePhone.ifBlank { entry.phone },
                addressLine = remoteAddress.ifBlank { entry.addressLine },
                city = remoteCity.ifBlank { entry.city },
                state = remoteState.ifBlank { entry.state },
                zipCode = remoteZip.ifBlank { entry.zipCode },
                country = remoteCountry.ifBlank { entry.country },
                passkeyBindings = remotePasskeyBindings.ifBlank { entry.passkeyBindings },
                sshKeyData = remoteSshKeyData.ifBlank { entry.sshKeyData },
                loginType = when {
                    takagi.ru.monica.data.model.ApiKeyEntryFields.isApiKey(customFields) -> takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE
                    takagi.ru.monica.data.model.GpgEntryFields.isGpg(customFields) -> "GPG_KEY"
                    isSteamMaFileEntry -> LOGIN_TYPE_STEAM_MAFILE
                    remoteSshKeyData.isNotBlank() -> LOGIN_TYPE_SSH_KEY
                    entry.loginType.equals(LOGIN_TYPE_STEAM_MAFILE, ignoreCase = true) -> "PASSWORD"
                    else -> entry.loginType
                },
                isFavorite = cipher.favorite,
                updatedAt = Date(),
                bitwardenVaultId = vaultId,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to update entry from cipher: ${e.message}")
            return null
        }
    }
    
    /**
     * 创建冲突备份
     */
    private suspend fun createConflictBackup(
        vault: BitwardenVault,
        entry: PasswordEntry,
        serverCipher: CipherApiResponse,
        conflictType: String
    ) {
        try {
            val localJson = json.encodeToString(
                mapOf(
                    "id" to entry.id.toString(),
                    "title" to entry.title,
                    "website" to entry.website,
                    "username" to entry.username,
                    "notes" to entry.notes
                    // 不包含密码等敏感数据的明文
                )
            )
            
            val serverJson = json.encodeToString(
                mapOf(
                    "id" to serverCipher.id,
                    "revisionDate" to serverCipher.revisionDate
                )
            )
            
            conflictDao.insert(
                BitwardenConflictBackup(
                    vaultId = vault.id,
                    entryId = entry.id,
                    bitwardenCipherId = serverCipher.id,
                    conflictType = conflictType,
                    localDataJson = localJson,
                    serverDataJson = serverJson,
                    localRevisionDate = entry.bitwardenRevisionDate,
                    serverRevisionDate = serverCipher.revisionDate,
                    entryTitle = entry.title,
                    description = strings.get(R.string.bitwarden_message_conflict_detail)
                )
            )
            
            android.util.Log.w(TAG, "Created conflict backup for entry ${entry.id}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create conflict backup: ${e.message}")
        }
    }
    
    /**
     * 解密文件夹名称
     */
    private fun decryptFolderName(encrypted: String?, key: SymmetricCryptoKey): String {
        if (encrypted.isNullOrBlank()) return "Unnamed Folder"
        return try {
            takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(encrypted, key)
        } catch (e: Exception) {
            "Unnamed Folder"
        }
    }
    
    /**
     * 解密字符串
     */
    private fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        return try {
            takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(encrypted, key)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 处理待处理的操作队列
     */
    suspend fun processPendingOperations(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Int = withContext(Dispatchers.IO) {
        val pendingOps = pendingOpDao.getRunnableOperationsByVault(vault.id)
        var processed = 0
        if (pendingOps.isNotEmpty()) {
            android.util.Log.i(
                TAG,
                "Processing ${pendingOps.size} pending operations for vault ${vault.id}"
            )
        }
        
        for (op in pendingOps) {
            try {
                if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                    android.util.Log.i(
                        TAG,
                        "Process delete op: vault=${vault.id}, itemType=${op.itemType}, entryId=${op.entryId}, cipherId=${op.bitwardenCipherId}"
                    )
                }
                val success = when (op.operationType) {
                    BitwardenPendingOperation.OP_CREATE -> processCreateOperation(vault, op, accessToken, symmetricKey)
                    BitwardenPendingOperation.OP_UPDATE -> processUpdateOperation(vault, op, accessToken, symmetricKey)
                    BitwardenPendingOperation.OP_DELETE -> processDeleteOperation(vault, op, accessToken)
                    BitwardenPendingOperation.OP_RESTORE -> processRestoreOperation(vault, op, accessToken)
                    else -> false
                }
                
                if (success) {
                    pendingOpDao.markCompleted(op.id)
                    processed++
                    if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                        android.util.Log.i(TAG, "Delete op completed: opId=${op.id}, cipherId=${op.bitwardenCipherId}")
                    }
                } else {
                    pendingOpDao.updateStatus(op.id, BitwardenPendingOperation.STATUS_FAILED)
                    if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                        android.util.Log.w(TAG, "Delete op failed: opId=${op.id}, cipherId=${op.bitwardenCipherId}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to process operation ${op.id}: ${e.message}")
                pendingOpDao.updateStatus(
                    id = op.id,
                    status = BitwardenPendingOperation.STATUS_FAILED,
                    lastError = e.message
                )
            }
        }
        
        processed
    }
    
    private suspend fun processCreateOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val entryId = op.entryId ?: return false
        val entry = passwordEntryDao.getPasswordEntryById(entryId) ?: return false
        return uploadLocalEntry(vault, entry, accessToken, symmetricKey).success
    }
    
    private suspend fun processUpdateOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val entryId = op.entryId ?: return false
        val entry = passwordEntryDao.getPasswordEntryById(entryId) ?: return false
        val cipherId = entry.bitwardenCipherId ?: return false
        return updateRemoteCipher(vault, entry, cipherId, accessToken, symmetricKey).success
    }
    
    private suspend fun processDeleteOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String
    ): Boolean {
        val cipherId = op.bitwardenCipherId ?: return false
        return deleteRemoteCipher(vault, cipherId, accessToken)
    }

    private suspend fun processRestoreOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String
    ): Boolean {
        val cipherId = op.bitwardenCipherId ?: return false
        return restoreRemoteCipher(vault, cipherId, accessToken)
    }
    
    // ========== 上传本地条目到 Bitwarden ==========
    
    /**
     * 上传本地创建的条目到 Bitwarden 服务器
     * 用于处理在 Monica 本地创建但标记为 Bitwarden 存储的条目
     */
    suspend fun uploadLocalEntries(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadResult = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "Checking for local entries to upload for vault ${vault.id}")

        val repairedCount = passwordEntryDao.markHistoricalPendingBitwardenUploads(vault.id)
        if (repairedCount > 0) {
            val line = "$TAG repairHistoricalPendingUploads: vaultId=${vault.id}, repaired=$repairedCount"
            android.util.Log.i(TAG, line)
            BitwardenDiagLogger.append(line)
        }
        
        var entriesToUpload = passwordEntryDao.getLocalEntriesPendingUpload(vault.id)
        if (entriesToUpload.isNotEmpty()) {
            val reconciled = reconcilePendingPasswordUploadsFromRemote(
                vault = vault,
                accessToken = accessToken,
                symmetricKey = symmetricKey,
                pendingEntries = entriesToUpload
            )
            if (reconciled > 0) {
                val line = "$TAG reconcilePendingPasswordUploadsFromRemote: vaultId=${vault.id}, reconciled=$reconciled"
                android.util.Log.i(TAG, line)
                BitwardenDiagLogger.append(line)
                entriesToUpload = passwordEntryDao.getLocalEntriesPendingUpload(vault.id)
            }
        }
        
        var uploaded = 0
        var failed = 0
        var authRequiredFailures = 0

        if (entriesToUpload.isNotEmpty()) {
            android.util.Log.i(TAG, "Found ${entriesToUpload.size} password entries to upload")
        }

        for (entry in entriesToUpload) {
            try {
                val result = uploadLocalEntry(vault, entry, accessToken, symmetricKey)
                if (result.success) {
                    uploaded++
                } else {
                    failed++
                    if (result.authRequired) {
                        authRequiredFailures++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to upload entry ${entry.id}: ${e.message}")
                failed++
                if (isMdkUnavailableException(e)) {
                    authRequiredFailures++
                }
            }
        }
        
        // 同步上传 SecureItems
        val secureResult = cipherUploadProcessor.uploadPendingSecureItems(vault, accessToken, symmetricKey)
        uploaded += secureResult.uploaded
        failed += secureResult.failed

        // 同步上传 Passkeys（仅新增）
        val passkeyResult = cipherUploadProcessor.uploadPendingPasskeys(vault, accessToken, symmetricKey)
        uploaded += passkeyResult.uploaded
        failed += passkeyResult.failed

        android.util.Log.i(
            TAG,
            "Upload complete: $uploaded uploaded, $failed failed (password + secure items + passkeys)"
        )
        UploadResult.Success(
            uploaded = uploaded,
            failed = failed,
            authRequiredFailures = authRequiredFailures
        )
    }

    /**
     * 上传本地已修改的 Bitwarden 条目（已有 cipherId）
     * 用于处理在 Monica 中编辑过的 Bitwarden 密码条目
     */
    suspend fun uploadModifiedEntries(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadResult = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "Checking for modified entries to upload for vault ${vault.id}")

        val modifiedEntries = passwordEntryDao
            .getEntriesWithPendingBitwardenSync(vault.id)
            .filter { !it.bitwardenCipherId.isNullOrBlank() }

        var uploaded = 0
        var failed = 0
        var authRequiredFailures = 0

        if (modifiedEntries.isNotEmpty()) {
            android.util.Log.i(TAG, "Found ${modifiedEntries.size} modified password entries to upload")
        }

        for (entry in modifiedEntries) {
            try {
                val cipherId = entry.bitwardenCipherId
                if (cipherId.isNullOrBlank()) {
                    failed++
                    continue
                }
                val result = updateRemoteCipher(vault, entry, cipherId, accessToken, symmetricKey)
                if (result.success) {
                    uploaded++
                } else {
                    failed++
                    if (result.authRequired) {
                        authRequiredFailures++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to upload modified entry ${entry.id}: ${e.message}")
                failed++
                if (isMdkUnavailableException(e)) {
                    authRequiredFailures++
                }
            }
        }

        // 同步上传已修改的 SecureItems
        val secureResult = cipherUploadProcessor.uploadModifiedSecureItems(vault, accessToken, symmetricKey)
        uploaded += secureResult.uploaded
        failed += secureResult.failed

        // 同步更新已存在的 Passkeys（修复历史 counter / userHandle 兼容）
        val passkeyResult = cipherUploadProcessor.uploadModifiedPasskeys(vault, accessToken, symmetricKey)
        uploaded += passkeyResult.uploaded
        failed += passkeyResult.failed

        android.util.Log.i(TAG, "Modified upload complete: $uploaded uploaded, $failed failed")
        failed += takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue(context)
            .flush(vault, accessToken, symmetricKey, apiManager)
        UploadResult.Success(
            uploaded = uploaded,
            failed = failed,
            authRequiredFailures = authRequiredFailures
        )
    }
    
    /**
     * 上传单个本地条目到 Bitwarden
     */
    private suspend fun uploadLocalEntry(
        vault: BitwardenVault,
        entry: PasswordEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadAttemptResult {
        val latestEntry = passwordEntryDao.getPasswordEntryById(entry.id) ?: entry
        if (!latestEntry.bitwardenCipherId.isNullOrBlank()) {
            android.util.Log.i(
                TAG,
                "Skip uploadLocalEntry for ${entry.id}: entry already bound to cipher ${latestEntry.bitwardenCipherId}"
            )
            return UploadAttemptResult(success = true)
        }

        // 构建加密的 Cipher 请求
        val createRequest = passwordEntryToCipherRequest(latestEntry, symmetricKey)
        val requestPayload = runCatching { json.encodeToString(createRequest) }.getOrNull()

        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = createRequest
            )

            if (!response.isSuccessful) {
                val errorPayload = runCatching { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "create cipher failed: ${response.code()} ${response.message()}"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "create",
                    entry = latestEntry,
                    cipherId = null,
                    responseCode = response.code(),
                    errorPayload = errorPayload,
                    throwable = null
                )
                android.util.Log.e(TAG, "Create cipher failed: ${response.code()} ${response.message()}")
                return UploadAttemptResult(success = false)
            }

            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "create",
                    entry = latestEntry,
                    cipherId = null,
                    responseCode = response.code(),
                    errorPayload = "empty-body",
                    throwable = null
                )
                return UploadAttemptResult(success = false)
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )

            // 更新本地条目，添加服务器返回的 cipherId 和 revisionDate
            val updatedEntry = latestEntry.copy(
                bitwardenCipherId = createdCipher.id,
                bitwardenRevisionDate = createdCipher.revisionDate,
                bitwardenLocalModified = false,
                updatedAt = Date()
            )
            passwordEntryDao.update(updatedEntry)
            passwordCustomFieldSyncState.markInitialized(vault.id, createdCipher.id)

            android.util.Log.d(TAG, "Successfully uploaded entry ${entry.id} as cipher ${createdCipher.id}")
            return UploadAttemptResult(success = true)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            appendPasswordUploadFailureLog(
                vaultId = vault.id,
                operation = "create",
                entry = latestEntry,
                cipherId = null,
                responseCode = null,
                errorPayload = null,
                throwable = e
            )
            android.util.Log.e(TAG, "Upload entry failed: ${e.message}", e)
            return UploadAttemptResult(
                success = false,
                authRequired = isMdkUnavailableException(e)
            )
        }
    }
    
    /**
     * 更新远程 Cipher
     */
    private suspend fun updateRemoteCipher(
        vault: BitwardenVault,
        entry: PasswordEntry,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadAttemptResult {
        val vaultApi = apiManager.getVaultApi(vault)
        val customFieldsInitialized = passwordCustomFieldSyncState.isInitialized(vault.id, cipherId)
        val baselineCipher = try {
            val remote = vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
            if (!remote.isSuccessful) {
                android.util.Log.w(
                    TAG,
                    "Fetch cipher baseline failed before update: cipherId=$cipherId code=${remote.code()}"
                )
                null
            } else {
                remote.body()
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Fetch cipher baseline exception before update: cipherId=$cipherId", error)
            null
        }
        if (baselineCipher == null && !customFieldsInitialized) {
            // 首次适配前缺少服务端基线时延后更新；随后执行的 /sync 会先导入远程字段。
            return UploadAttemptResult(success = false)
        }
        val mergedFields = baselineCipher?.let { baseline ->
            mergeCipherFieldsPreservingUnknown(
                entry = entry,
                remoteFields = baseline.fields,
                symmetricKey = symmetricKey,
                initialized = customFieldsInitialized
            )
        }

        val updateRequest = passwordEntryToCipherUpdateRequest(
            entry = entry,
            symmetricKey = symmetricKey,
            mergedFields = mergedFields
        )
        val requestPayload = runCatching { json.encodeToString(updateRequest) }.getOrNull()

        try {
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                val errorPayload = runCatching { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "update",
                    entry = entry,
                    cipherId = cipherId,
                    responseCode = response.code(),
                    errorPayload = errorPayload,
                    throwable = null
                )
                android.util.Log.e(TAG, "Update cipher failed: ${response.code()}")
                return UploadAttemptResult(success = false)
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "update",
                    entry = entry,
                    cipherId = cipherId,
                    responseCode = response.code(),
                    errorPayload = "empty-body",
                    throwable = null
                )
                return UploadAttemptResult(success = false)
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )

            // 更新本地条目
            val updatedEntry = entry.copy(
                bitwardenRevisionDate = updatedCipher.revisionDate,
                bitwardenLocalModified = false,
                updatedAt = Date()
            )
            passwordEntryDao.update(updatedEntry)
            passwordCustomFieldSyncState.markInitialized(vault.id, cipherId)

            logBitwardenPasswordEditHistory(
                vaultId = vault.id,
                cipherId = cipherId,
                title = entry.title,
                baselineCipher = baselineCipher,
                updateRequest = updateRequest,
                symmetricKey = symmetricKey
            )

            return UploadAttemptResult(success = true)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            appendPasswordUploadFailureLog(
                vaultId = vault.id,
                operation = "update",
                entry = entry,
                cipherId = cipherId,
                responseCode = null,
                errorPayload = null,
                throwable = e
            )
            android.util.Log.e(TAG, "Update remote cipher failed: ${e.message}", e)
            return UploadAttemptResult(
                success = false,
                authRequired = isMdkUnavailableException(e)
            )
        }
    }

    private data class UploadAttemptResult(
        val success: Boolean,
        val authRequired: Boolean = false
    )

    private fun isMdkUnavailableException(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if (message.contains("mdk not available")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun captureRawExchange(
        vaultId: Long,
        operation: String,
        method: String,
        endpoint: String,
        requestBody: String?,
        responseCode: Int?,
        responseBody: String?,
        success: Boolean,
        error: String? = null
    ) {
        runCatching {
            BitwardenSyncForensicsLogger.captureRawExchange(
                context = context,
                vaultId = vaultId,
                operation = operation,
                method = method,
                endpoint = endpoint,
                requestBody = requestBody,
                responseCode = responseCode,
                responseBody = responseBody,
                success = success,
                errorMessage = error
            )
        }.onFailure { captureError ->
            android.util.Log.w(TAG, "Capture raw exchange failed: ${captureError.message}")
        }
    }

    private fun logBitwardenPasswordEditHistory(
        vaultId: Long,
        cipherId: String,
        title: String,
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ) {
        val changes = buildPasswordEditHistoryChanges(
            baselineCipher = baselineCipher,
            updateRequest = updateRequest,
            symmetricKey = symmetricKey
        )
        if (changes.isEmpty()) return

        OperationLogger.logUpdate(
            itemType = OperationLogItemType.BITWARDEN_SYNC,
            itemId = buildBitwardenItemId(vaultId, cipherId),
            itemTitle = title.ifBlank { "Bitwarden Password Entry" },
            changes = changes
        )
    }

    private fun buildPasswordEditHistoryChanges(
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()

        appendIfChanged(
            changes = changes,
            fieldName = "title",
            oldValue = decryptOrPlain(baselineCipher?.name, symmetricKey),
            newValue = decryptOrPlain(updateRequest.name, symmetricKey),
            sensitive = false
        )
        appendIfChanged(
            changes = changes,
            fieldName = "username",
            oldValue = decryptOrPlain(baselineCipher?.login?.username, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.username, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "password",
            oldValue = decryptOrPlain(baselineCipher?.login?.password, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.password, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "totp",
            oldValue = decryptOrPlain(baselineCipher?.login?.totp, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.totp, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "notes",
            oldValue = decryptOrPlain(baselineCipher?.notes, symmetricKey),
            newValue = decryptOrPlain(updateRequest.notes, symmetricKey),
            sensitive = true
        )

        val oldUriCount = baselineCipher?.login?.uris?.size ?: 0
        val newUriCount = updateRequest.login?.uris?.size ?: 0
        if (oldUriCount != newUriCount) {
            changes += FieldChange("login_uri_count", oldUriCount.toString(), newUriCount.toString())
        }

        val oldFavorite = baselineCipher?.favorite ?: false
        val newFavorite = updateRequest.favorite ?: false
        if (oldFavorite != newFavorite) {
            changes += FieldChange("favorite", oldFavorite.toString(), newFavorite.toString())
        }

        val oldArchived = baselineCipher?.archivedDate.orEmpty().ifBlank { "active" }
        val newArchived = updateRequest.archivedDate.orEmpty().ifBlank { "active" }
        if (oldArchived != newArchived) {
            changes += FieldChange("archived", oldArchived, newArchived)
        }

        val oldFieldSummary = summarizeFieldCollection(baselineCipher?.fields, symmetricKey)
        val newFieldSummary = summarizeFieldCollection(updateRequest.fields, symmetricKey)
        if (oldFieldSummary != newFieldSummary) {
            changes += FieldChange("custom_fields", oldFieldSummary, newFieldSummary)
        }

        return changes
    }

    private fun summarizeFieldCollection(
        fields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): String {
        if (fields.isNullOrEmpty()) return "count=0"

        val nameSamples = fields.mapNotNull { field ->
            decryptOrPlain(field.name, symmetricKey)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.replace("\n", " ")
                ?.replace("\r", "")
                ?.take(24)
        }.distinct().sorted().take(4)

        val signatures = fields.map { field ->
            val plainName = decryptOrPlain(field.name, symmetricKey).orEmpty().trim()
            val plainValue = decryptOrPlain(field.value, symmetricKey)
            val summarizedValue = summarizeHistoryValue(plainValue, sensitive = true)
            "${field.type}|${field.linkedId}|$plainName|$summarizedValue"
        }.sorted()

        val sampleText = nameSamples.joinToString("|").ifBlank { "-" }
        return "count=${fields.size},names=$sampleText,sha=${shortSha(signatures.joinToString("||"))}"
    }

    private fun appendIfChanged(
        changes: MutableList<FieldChange>,
        fieldName: String,
        oldValue: String?,
        newValue: String?,
        sensitive: Boolean
    ) {
        val oldNormalized = oldValue.orEmpty()
        val newNormalized = newValue.orEmpty()
        if (oldNormalized == newNormalized) return

        changes += FieldChange(
            fieldName = fieldName,
            oldValue = summarizeHistoryValue(oldNormalized, sensitive),
            newValue = summarizeHistoryValue(newNormalized, sensitive)
        )
    }

    private fun summarizeHistoryValue(value: String?, sensitive: Boolean): String {
        val normalized = value.orEmpty()
        if (normalized.isBlank()) return "(empty)"

        if (sensitive) {
            return summarizeSensitiveHistoryValue(normalized)
        }

        val sanitized = normalized
            .replace("\n", "\\n")
            .replace("\r", "")
        if (sanitized.length <= 180) {
            return sanitized
        }
        val head = sanitized.take(120)
        val tail = sanitized.takeLast(40)
        val omitted = (sanitized.length - 160).coerceAtLeast(0)
        return "$head...(omitted=$omitted)...$tail"
    }

    private fun summarizeSensitiveHistoryValue(normalized: String): String {
        val lower = normalized.count { it.isLowerCase() }
        val upper = normalized.count { it.isUpperCase() }
        val digits = normalized.count { it.isDigit() }
        val spaces = normalized.count { it.isWhitespace() }
        val symbols = (normalized.length - lower - upper - digits - spaces).coerceAtLeast(0)
        val lines = normalized.count { it == '\n' } + 1
        val format = detectSensitiveFormat(normalized)
        val pattern = buildClassPattern(normalized)

        return "len=${normalized.length},sha=${shortSha(normalized)},fmt=$format,lines=$lines,mix=u$upper/l$lower/d$digits/s$symbols,pat=$pattern"
    }

    private fun detectSensitiveFormat(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "empty"
        return when {
            trimmed.startsWith("otpauth://", ignoreCase = true) -> "otpauth"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> "url"
            trimmed.startsWith("{") && trimmed.endsWith("}") -> "json_like"
            trimmed.contains('@') && !trimmed.contains(' ') -> "email_like"
            trimmed.all { it.isDigit() } -> "digits"
            trimmed.contains("\n") -> "multiline"
            else -> "text"
        }
    }

    private fun buildClassPattern(value: String, maxLen: Int = 24): String {
        val pattern = value.take(maxLen).map { ch ->
            when {
                ch.isUpperCase() -> 'A'
                ch.isLowerCase() -> 'a'
                ch.isDigit() -> '0'
                ch.isWhitespace() -> '_'
                else -> '#'
            }
        }.joinToString("")
        return if (value.length > maxLen) "$pattern..." else pattern
    }

    private fun shortSha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun buildBitwardenItemId(vaultId: Long, cipherId: String): Long {
        return "${vaultId}:$cipherId".hashCode().toLong() and 0x7FFFFFFFL
    }

    private fun buildSyncFullRawSummary(response: SyncResponse): String {
        return json.encodeToString(
            SyncFullRawSummary(
                profileIdDigest = response.profile.id.takeIf { it.isNotBlank() }?.let { shortSha(it) },
                emailDigest = response.profile.email.takeIf { it.isNotBlank() }?.let { shortSha(it.lowercase()) },
                premium = response.profile.hasPremium,
                folderCount = response.folders.size,
                cipherCount = response.ciphers.size,
                activeCipherCount = response.ciphers.count { it.deletedDate == null },
                collectionCount = response.collections?.size ?: 0,
                policyCount = response.policies?.size ?: 0,
                sendCount = response.sends?.size ?: 0
            )
        )
    }
    
    /**
     * 删除远程 Cipher
     */
    private suspend fun deleteRemoteCipher(
        vault: BitwardenVault,
        cipherId: String,
        accessToken: String
    ): Boolean {
        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.deleteCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
             
            return response.isSuccessful || response.code() == 404
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Delete remote cipher failed: ${e.message}", e)
            return false
        }
    }

    private suspend fun restoreRemoteCipher(
        vault: BitwardenVault,
        cipherId: String,
        accessToken: String
    ): Boolean {
        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.restoreCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )

            return response.isSuccessful || response.code() == 400 || response.code() == 404
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Restore remote cipher failed: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 将 PasswordEntry 转换为加密的 CipherCreateRequest
     *
     * SSH 密钥使用 Type 1 (Login) + 自定义字段上传，而非 Type 5。
     * 原因：Vaultwarden 等兼容服务端不支持 Type 5，会在 /sync 时将其
     * 降级为 Type 1 并丢弃 sshKey 数据，导致同步回来后 SSH 密钥丢失。
     * 使用 Type 1 + 自定义字段可确保数据在服务端完整保留并正确往返。
     */
    private suspend fun passwordEntryToCipherRequest(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val crypto = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
        if (entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) {
            // 使用 Type 1 + 自定义字段，兼容不支持 Type 5 的服务端
            val encryptedName = crypto.encryptString(entry.title, symmetricKey)
            val encryptedNotes = entry.notes.takeIf { it.isNotBlank() }?.let {
                crypto.encryptString(it, symmetricKey)
            }
            return CipherCreateRequest(
                type = 1,
                folderId = entry.bitwardenFolderId,
                name = encryptedName,
                notes = encryptedNotes,
                login = CipherLoginApiData(
                    uris = emptyList(),
                    fido2Credentials = emptyList()
                ),
                fields = buildEncryptedPasswordCustomFields(entry, symmetricKey),
                favorite = entry.isFavorite,
                archivedDate = toBitwardenArchivedDate(entry)
            )
        }

        val plainPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        
        // 加密各个字段
        val encryptedName = crypto.encryptString(entry.title, symmetricKey)
        val encryptedNotes = if (entry.notes.isNotBlank()) {
            crypto.encryptString(entry.notes, symmetricKey)
        } else null
        
        // 构建 Login 数据
        val loginData = CipherLoginApiData(
            username = if (entry.username.isNotBlank()) {
                crypto.encryptString(entry.username, symmetricKey)
            } else null,
            password = if (plainPassword.isNotBlank()) {
                crypto.encryptString(plainPassword, symmetricKey)
            } else null,
            passwordRevisionDate = if (plainPassword.isNotBlank()) {
                java.time.Instant.now().toString()
            } else null,
            totp = if (entry.authenticatorKey.isNotBlank()) {
                crypto.encryptString(resolveBitwardenTotpPayload(entry), symmetricKey)
            } else null,
            uris = buildEncryptedLoginUris(entry, symmetricKey) ?: emptyList(),
            fido2Credentials = emptyList()
        )
        
        return CipherCreateRequest(
            type = 1, // Login type
            folderId = entry.bitwardenFolderId,
            name = encryptedName,
            notes = encryptedNotes,
            login = loginData,
            fields = buildEncryptedPasswordCustomFields(entry, symmetricKey),
            favorite = entry.isFavorite,
            archivedDate = toBitwardenArchivedDate(entry)
        )
    }
    
    /**
     * 将 PasswordEntry 转换为加密的 CipherUpdateRequest
     */
    private suspend fun passwordEntryToCipherUpdateRequest(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey,
        mergedFields: List<CipherFieldApiData>? = null
    ): CipherUpdateRequest {
        val crypto = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
        if (entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) {
            // 使用 Type 1 + 自定义字段，兼容不支持 Type 5 的服务端
            val encryptedName = crypto.encryptString(entry.title, symmetricKey)
            val encryptedNotes = entry.notes.takeIf { it.isNotBlank() }?.let {
                crypto.encryptString(it, symmetricKey)
            }
            return CipherUpdateRequest(
                type = 1,
                folderId = entry.bitwardenFolderId,
                name = encryptedName,
                notes = encryptedNotes,
                login = CipherLoginApiData(
                    uris = emptyList(),
                    fido2Credentials = emptyList()
                ),
                fields = mergedFields ?: buildEncryptedPasswordCustomFields(entry, symmetricKey),
                favorite = entry.isFavorite,
                archivedDate = toBitwardenArchivedDate(entry)
            )
        }

        val plainPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        
        val encryptedName = crypto.encryptString(entry.title, symmetricKey)
        val encryptedNotes = if (entry.notes.isNotBlank()) {
            crypto.encryptString(entry.notes, symmetricKey)
        } else null
        
        val loginData = CipherLoginApiData(
            username = if (entry.username.isNotBlank()) {
                crypto.encryptString(entry.username, symmetricKey)
            } else null,
            password = if (plainPassword.isNotBlank()) {
                crypto.encryptString(plainPassword, symmetricKey)
            } else null,
            passwordRevisionDate = if (plainPassword.isNotBlank()) {
                java.time.Instant.now().toString()
            } else null,
            totp = if (entry.authenticatorKey.isNotBlank()) {
                crypto.encryptString(resolveBitwardenTotpPayload(entry), symmetricKey)
            } else null,
            uris = buildEncryptedLoginUris(entry, symmetricKey) ?: emptyList(),
            fido2Credentials = emptyList()
        )
        
        return CipherUpdateRequest(
            type = 1,
            folderId = entry.bitwardenFolderId,
            name = encryptedName,
            notes = encryptedNotes,
            login = loginData,
            fields = mergedFields ?: buildEncryptedPasswordCustomFields(entry, symmetricKey),
            favorite = entry.isFavorite,
            archivedDate = toBitwardenArchivedDate(entry)
        )
    }

    private fun resolveBitwardenTotpPayload(entry: PasswordEntry): String {
        val plainAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(
            storedValue = entry.authenticatorKey,
            entryId = entry.id,
            fieldName = "authenticatorKey"
        )
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = plainAuthenticatorKey,
            fallbackIssuer = entry.website,
            fallbackAccountName = entry.username
        )?.let { resolved ->
            TotpDataResolver.toBitwardenPayload(entry.title, resolved)
        } ?: plainAuthenticatorKey
    }

    private fun toBitwardenArchivedDate(entry: PasswordEntry): String? {
        if (!entry.isArchived) return null
        val archiveDate = entry.archivedAt ?: entry.updatedAt
        return runCatching {
            Instant.ofEpochMilli(archiveDate.time).toString()
        }.getOrNull()
    }

    private fun parseRevisionMillis(revisionDate: String?): Long? {
        if (revisionDate.isNullOrBlank()) return null
        return runCatching { Instant.parse(revisionDate).toEpochMilli() }.getOrNull()
    }

    private suspend fun mergeCipherFieldsPreservingUnknown(
        entry: PasswordEntry,
        remoteFields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey,
        initialized: Boolean
    ): List<CipherFieldApiData>? {
        val remoteApiFields = remoteFields.orEmpty()
        val remotePlainFields = remoteApiFields.map { field ->
            BitwardenPlainCustomField(
                name = decryptOrPlain(field.name, symmetricKey)?.trim(),
                value = decryptOrPlain(field.value, symmetricKey),
                type = field.type,
                linkedId = field.linkedId
            )
        }
        val localUserFields = loadPasswordCustomFields(entry.id)
        val outgoingUserFields = if (initialized) {
            localUserFields
        } else {
            BitwardenPasswordCustomFieldAdapter.mergeIncoming(
                local = localUserFields,
                remote = BitwardenPasswordCustomFieldAdapter.extractUserFields(remotePlainFields),
                sameRevision = true
            ).fields
        }
        if (outgoingUserFields != localUserFields) {
            replacePasswordCustomFields(entry.id, outgoingUserFields)
        }

        val localFields = buildEncryptedPasswordCustomFields(
            entry = entry,
            symmetricKey = symmetricKey,
            userFieldsOverride = outgoingUserFields
        ).orEmpty()
        val localSystemFieldNames = buildPasswordSystemFields(entry).mapTo(linkedSetOf()) { it.name }
        val preservedIndexes = BitwardenPasswordCustomFieldAdapter.remoteIndexesToPreserveForUpload(
            remote = remotePlainFields,
            local = outgoingUserFields,
            localSystemFieldNames = localSystemFieldNames,
            initialized = initialized
        )
        val preservedRemote = remoteApiFields.filterIndexed { index, _ -> index in preservedIndexes }

        return (preservedRemote + localFields).ifEmpty { null }
    }

    private fun buildEncryptedLoginUris(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherUriApiData>? {
        val crypto = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
        val uris = mutableListOf<CipherUriApiData>()
        PasswordWebsiteCodec.parse(entry.website)
            .filter { it.isNotBlank() }
            .map(::normalizeBitwardenWebsite)
            .distinct()
            .forEach { normalizedWebsite ->
                uris.add(
                    CipherUriApiData(
                        uri = crypto.encryptString(normalizedWebsite, symmetricKey),
                        match = null
                    )
                )
            }
        if (entry.appPackageName.isNotBlank()) {
            val pkg = entry.appPackageName.removePrefix("androidapp://")
            uris.add(
                CipherUriApiData(
                    uri = crypto.encryptString("androidapp://$pkg", symmetricKey),
                    match = null
                )
            )
        }
        return uris.ifEmpty { null }
    }

    private fun normalizeBitwardenWebsite(rawWebsite: String): String {
        val trimmed = rawWebsite.trim()
        if (trimmed.isBlank()) return trimmed
        return if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("androidapp://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private suspend fun reconcilePendingPasswordUploadsFromRemote(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        pendingEntries: List<PasswordEntry>
    ): Int {
        if (pendingEntries.isEmpty()) return 0

        return runCatching {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.sync(
                authorization = "Bearer $accessToken",
                excludeDomains = true
            )
            if (!response.isSuccessful) {
                android.util.Log.w(
                    TAG,
                    "Failed to reconcile pending uploads before create: sync code=${response.code()}"
                )
                return@runCatching 0
            }

            val syncResponse = response.body() ?: return@runCatching 0
            val remoteLoginCiphers = syncResponse.ciphers
                .filter { it.type == 1 && it.deletedDate == null && !it.id.isBlank() }

            var reconciled = 0
            for (entry in pendingEntries) {
                val current = passwordEntryDao.getPasswordEntryById(entry.id) ?: continue
                if (!current.bitwardenCipherId.isNullOrBlank()) continue

                val matches = remoteLoginCiphers.filter { cipher ->
                    pendingEntryMatchesRemoteCipher(current, cipher, symmetricKey)
                }
                if (matches.size != 1) continue

                val matched = matches.first()
                val localUserFields = loadPasswordCustomFields(current.id)
                val remoteUserFields = BitwardenPasswordCustomFieldAdapter.extractUserFields(
                    matched.fields.orEmpty().map { field ->
                        BitwardenPlainCustomField(
                            name = decryptOrPlain(field.name, symmetricKey)?.trim(),
                            value = decryptOrPlain(field.value, symmetricKey),
                            type = field.type,
                            linkedId = field.linkedId
                        )
                    }
                )
                val customFieldMerge = BitwardenPasswordCustomFieldAdapter.mergeIncoming(
                    local = localUserFields,
                    remote = remoteUserFields,
                    sameRevision = true
                )
                val rebound = current.copy(
                    bitwardenCipherId = matched.id,
                    bitwardenFolderId = matched.folderId,
                    bitwardenRevisionDate = matched.revisionDate,
                    bitwardenCipherType = matched.type,
                    bitwardenLocalModified = customFieldMerge.needsUpload,
                    updatedAt = Date()
                )
                passwordEntryDao.update(rebound)
                replacePasswordCustomFields(current.id, customFieldMerge.fields)
                passwordCustomFieldSyncState.markInitialized(vault.id, matched.id)
                if (customFieldMerge.needsUpload) {
                    takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge.requestLocalMutationSync(
                        context = context,
                        vaultId = vault.id
                    )
                }
                reconciled++
            }

            reconciled
        }.getOrElse { error ->
            android.util.Log.w(
                TAG,
                "Pending upload reconciliation skipped: ${error.message}",
                error
            )
            0
        }
    }

    private fun pendingEntryMatchesRemoteCipher(
        entry: PasswordEntry,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val login = cipher.login ?: return false
        val remoteTitle = decryptString(cipher.name, symmetricKey) ?: return false
        val remoteUsername = decryptString(login.username, symmetricKey).orEmpty()
        val remotePassword = decryptString(login.password, symmetricKey).orEmpty()
        val remoteNotes = decryptString(cipher.notes, symmetricKey).orEmpty()
        val remoteTotp = decryptString(login.totp, symmetricKey).orEmpty()
        val remoteUris = parseLoginUris(login.uris, symmetricKey)
        val remoteAppPackage = remoteUris.appPackageName.trim()
        val localWebsites = PasswordWebsiteCodec.parse(entry.website)
            .filter { it.isNotBlank() }
            .map(::normalizeWebsiteKey)
            .distinct()
        val remoteWebsites = PasswordWebsiteCodec.parse(remoteUris.website)
            .filter { it.isNotBlank() }
            .map(::normalizeWebsiteKey)
            .distinct()
        val localPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        val localAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(
            storedValue = entry.authenticatorKey,
            entryId = entry.id,
            fieldName = "authenticatorKey"
        )

        return entry.title == remoteTitle &&
            entry.username == remoteUsername &&
            localPassword == remotePassword &&
            entry.notes == remoteNotes &&
            localAuthenticatorKey == remoteTotp &&
            entry.isFavorite == cipher.favorite &&
            entry.bitwardenFolderId == cipher.folderId &&
            entry.appPackageName.trim() == remoteAppPackage &&
            localWebsites == remoteWebsites
    }

    private fun appendPasswordUploadFailureLog(
        vaultId: Long,
        operation: String,
        entry: PasswordEntry,
        cipherId: String?,
        responseCode: Int?,
        errorPayload: String?,
        throwable: Throwable?
    ) {
        val summary = buildString {
            append(TAG)
            append(" passwordUploadFailure")
            append(": vaultId=")
            append(vaultId)
            append(", op=")
            append(operation)
            append(", entryId=")
            append(entry.id)
            append(", cipherId=")
            append(cipherId ?: "pending")
            append(", hasFolder=")
            append(!entry.bitwardenFolderId.isNullOrBlank())
            append(", website=")
            append(
                PasswordWebsiteCodec.parse(entry.website)
                    .filter { it.isNotBlank() }
                    .map(::normalizeBitwardenWebsite)
                    .joinToString(", ")
                    .take(120)
            )
            responseCode?.let {
                append(", code=")
                append(it)
            }
            errorPayload?.takeIf { it.isNotBlank() }?.let {
                append(", errorPayload=")
                append(it.take(240))
            }
            throwable?.let {
                append(", exception=")
                append(it.javaClass.simpleName)
                append(":")
                append(it.message?.take(180) ?: "unknown")
            }
        }
        BitwardenDiagLogger.append(summary)
    }

    private data class PasswordPlainUploadField(
        val name: String,
        val value: String,
        val type: Int = BitwardenPasswordCustomFieldAdapter.TYPE_TEXT
    )

    private suspend fun buildEncryptedPasswordCustomFields(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey,
        userFieldsOverride: List<MonicaPlainCustomField>? = null
    ): List<CipherFieldApiData>? {
        val crypto = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
        val userFields = userFieldsOverride ?: loadPasswordCustomFields(entry.id)
        val fields = buildPasswordSystemFields(entry) + userFields
            .filter { it.name.isNotBlank() }
            .map { field ->
                PasswordPlainUploadField(
                    name = field.name,
                    value = field.value,
                    type = if (field.isProtected) {
                        BitwardenPasswordCustomFieldAdapter.TYPE_HIDDEN
                    } else {
                        BitwardenPasswordCustomFieldAdapter.TYPE_TEXT
                    }
                )
            }

        return fields.map { field ->
            CipherFieldApiData(
                name = crypto.encryptString(field.name, symmetricKey),
                value = crypto.encryptString(field.value, symmetricKey),
                type = field.type
            )
        }.ifEmpty { null }
    }

    private fun buildPasswordSystemFields(entry: PasswordEntry): List<PasswordPlainUploadField> {
        val fields = mutableListOf<PasswordPlainUploadField>()

        fun addField(
            name: String,
            value: String,
            type: Int = BitwardenPasswordCustomFieldAdapter.TYPE_TEXT
        ) {
            if (value.isNotBlank()) {
                fields += PasswordPlainUploadField(name = name, value = value, type = type)
            }
        }

        if (entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) {
            SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
                addField("monica_ssh_algorithm", ssh.algorithm)
                addField("monica_ssh_key_size", ssh.keySize.takeIf { it > 0 }?.toString().orEmpty())
                addField("monica_ssh_public_key", ssh.publicKeyOpenSsh)
                addField(
                    "monica_ssh_private_key",
                    ssh.privateKeyOpenSsh,
                    BitwardenPasswordCustomFieldAdapter.TYPE_HIDDEN
                )
                addField("monica_ssh_fingerprint", ssh.fingerprintSha256)
                addField("monica_ssh_comment", ssh.comment)
                addField("monica_ssh_format", ssh.format)
            }
            addField("monica_login_type", LOGIN_TYPE_SSH_KEY)
            return fields
        }

        addField("monica_app_package", entry.appPackageName)
        addField("appPackageName", entry.appPackageName)
        addField("monica_app_name", entry.appName)
        addField("appName", entry.appName)
        addField("monica_email", entry.email)
        addField("email", entry.email)
        addField("monica_phone", entry.phone)
        addField("phone", entry.phone)
        addField("monica_address_line", entry.addressLine)
        addField("monica_city", entry.city)
        addField("monica_state", entry.state)
        addField("monica_zip_code", entry.zipCode)
        addField("monica_country", entry.country)
        addField("monica_passkey_bindings", entry.passkeyBindings)
        SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
            addField("monica_ssh_algorithm", ssh.algorithm)
            addField("monica_ssh_key_size", ssh.keySize.takeIf { it > 0 }?.toString().orEmpty())
            addField("monica_ssh_public_key", ssh.publicKeyOpenSsh)
            addField("monica_ssh_private_key", ssh.privateKeyOpenSsh)
            addField("monica_ssh_fingerprint", ssh.fingerprintSha256)
            addField("monica_ssh_comment", ssh.comment)
            addField("monica_ssh_format", ssh.format)
        }

        val legacyAddress = listOf(entry.addressLine, entry.city, entry.state, entry.zipCode, entry.country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        addField("address", legacyAddress)
        return fields
    }

    private suspend fun loadPasswordCustomFields(entryId: Long): List<MonicaPlainCustomField> {
        if (entryId <= 0L) return emptyList()
        return customFieldDao.getFieldsByEntryIdSync(entryId).map { field ->
            MonicaPlainCustomField(
                name = field.title,
                value = field.value,
                isProtected = field.isProtected
            )
        }
    }

    private suspend fun replacePasswordCustomFields(
        entryId: Long,
        fields: List<MonicaPlainCustomField>
    ) {
        if (entryId <= 0L) return
        val existing = loadPasswordCustomFields(entryId)
        if (existing == fields) return

        customFieldDao.replaceFieldsForEntry(
            entryId = entryId,
            newFields = fields.mapIndexed { index, field ->
                takagi.ru.monica.data.CustomField(
                    entryId = entryId,
                    title = field.name,
                    value = field.value,
                    isProtected = field.isProtected,
                    sortOrder = index
                )
            }
        )
    }

    private fun parseLoginUris(
        uris: List<CipherUriApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): ParsedLoginUris {
        if (uris.isNullOrEmpty()) return ParsedLoginUris()

        val websites = linkedSetOf<String>()
        var appPackageName = ""
        uris.forEach { uriData ->
            val uri = decryptString(uriData.uri, symmetricKey) ?: return@forEach
            when {
                uri.startsWith("androidapp://", ignoreCase = true) -> {
                    if (appPackageName.isBlank()) {
                        appPackageName = uri.removePrefix("androidapp://")
                    }
                }
                else -> websites += normalizeBitwardenWebsite(uri)
            }
        }
        return ParsedLoginUris(
            website = PasswordWebsiteCodec.encode(websites.toList()),
            appPackageName = appPackageName
        )
    }

    private fun parsePasswordCustomFieldMap(
        fields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): Map<String, String> {
        if (fields.isNullOrEmpty()) return emptyMap()
        return buildMap {
            fields.forEach { field ->
                val name = decryptOrPlain(field.name, symmetricKey).orEmpty().trim()
                if (name.isBlank()) return@forEach
                val value = decryptOrPlain(field.value, symmetricKey).orEmpty()
                put(name, value)
            }
        }
    }

    private fun decryptOrPlain(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return null
        val decrypted = decryptString(value, key)
        if (decrypted != null) return decrypted
        if (looksLikeCipherString(value)) return null
        return value
    }

    private fun looksLikeCipherString(value: String): Boolean {
        return CIPHER_STRING_PATTERN.matches(value)
    }

    private fun buildSshKeyDataFromCustomFields(fields: Map<String, String>): String {
        // 1. 先按字段名匹配（Monica 自己上传的格式）
        val publicKey = fields.findSshField(
            "monica_ssh_public_key",
            "publicKey",
            "public_key",
            "public key",
            "public-key",
            "ssh public key",
            "ssh_public_key"
        )
        val privateKey = fields.findSshField(
            "monica_ssh_private_key",
            "privateKey",
            "private_key",
            "private key",
            "private-key",
            "ssh private key",
            "ssh_private_key"
        )
        val fingerprint = fields.findSshField(
            "monica_ssh_fingerprint",
            "keyFingerprint",
            "key_fingerprint",
            "key fingerprint",
            "key-fingerprint",
            "fingerprint",
            "ssh fingerprint",
            "ssh_fingerprint"
        )

        // 2. 如果按名称没找到，尝试按值的内容特征识别（兼容 Bitwarden 等第三方客户端）
        val resolvedPublicKey = publicKey.ifBlank { fields.findValueByContent(::looksLikeSshPublicKey) }
        val resolvedPrivateKey = privateKey.ifBlank { fields.findValueByContent(::looksLikeSshPrivateKey) }
        val resolvedFingerprint = fingerprint.ifBlank { fields.findValueByContent(::looksLikeSshFingerprint) }

        val algorithm = fields.findSshField("monica_ssh_algorithm", "algorithm", "key type", "key-type", "keyType")
            .ifBlank { inferSshAlgorithm(resolvedPublicKey) }

        return SshKeyDataCodec.encode(
            SshKeyData(
                algorithm = algorithm,
                keySize = fields["monica_ssh_key_size"]?.toIntOrNull() ?: 0,
                publicKeyOpenSsh = resolvedPublicKey,
                privateKeyOpenSsh = resolvedPrivateKey,
                fingerprintSha256 = resolvedFingerprint,
                comment = fields["monica_ssh_comment"].orEmpty(),
                format = fields["monica_ssh_format"].orEmpty().ifBlank { SshKeyData.FORMAT_OPENSSH }
            )
        )
    }

    private fun looksLikeSshPublicKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("ssh-rsa ") ||
            trimmed.startsWith("ssh-ed25519 ") ||
            trimmed.startsWith("ecdsa-sha2-") ||
            trimmed.startsWith("ssh-dss ")
    }

    private fun looksLikeSshPrivateKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("-----BEGIN") &&
            (trimmed.contains("PRIVATE KEY") || trimmed.contains("OPENSSH"))
    }

    private fun looksLikeSshFingerprint(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("SHA256:") ||
            trimmed.startsWith("MD5:") ||
            (trimmed.length in 40..50 && trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    private fun Map<String, String>.findValueByContent(predicate: (String) -> Boolean): String {
        return values.firstOrNull { it.isNotBlank() && predicate(it) }.orEmpty()
    }

    private fun Map<String, String>.findSshField(vararg names: String): String {
        names.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }?.let { return it }
        val normalizedNames = names.map { normalizeSshFieldName(it) }.toSet()
        return entries.firstOrNull { (name, value) ->
            value.isNotBlank() && normalizeSshFieldName(name) in normalizedNames
        }?.value.orEmpty()
    }

    private fun normalizeSshFieldName(name: String): String {
        return name.filter { it.isLetterOrDigit() }.lowercase()
    }

    private fun inferSshAlgorithm(publicKey: String): String {
        return when {
            publicKey.startsWith("ssh-rsa") -> SshKeyData.ALGORITHM_RSA
            publicKey.startsWith("ssh-ed25519") -> SshKeyData.ALGORITHM_ED25519
            else -> ""
        }
    }

    /**
     * Resolve local stored password to plain text before uploading to Bitwarden.
     *
     * Local DB stores encrypted payloads. If we upload that payload directly,
     * it will be encrypted again by Bitwarden and eventually show as "garbled" text.
     */
    private fun resolvePlainPasswordForBitwardenUpload(storedPassword: String, entryId: Long): String {
        if (storedPassword.isBlank()) return ""

        var candidate = storedPassword
        repeat(3) {
            val decrypted = try {
                securityManager.decryptData(candidate)
            } catch (e: Exception) {
                // For prefixed payloads, decrypt failure means auth/key state is invalid.
                // Failing closed avoids uploading ciphertext as if it were plaintext.
                if (
                    candidate.startsWith("MDK|") ||
                    candidate.startsWith("V2|") ||
                    candidate.startsWith("C2|")
                ) {
                    throw IllegalStateException(
                        "Cannot decrypt local password for Bitwarden upload, entryId=$entryId",
                        e
                    )
                }
                return candidate
            }

            if (decrypted == candidate) {
                return candidate
            }
            candidate = decrypted
        }
        return candidate
    }

    private fun resolvePlainStoredSensitiveValueForBitwardenUpload(
        storedValue: String,
        entryId: Long,
        fieldName: String
    ): String {
        if (storedValue.isBlank()) return ""
        if (!securityManager.looksLikeMonicaCiphertext(storedValue)) return storedValue

        return runCatching { securityManager.decryptData(storedValue) }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot decrypt local $fieldName for Bitwarden upload, entryId=$entryId",
                error
            )
        }
    }

    private fun encodeBitwardenTotpForLocalStorage(totpPayload: String): String {
        if (totpPayload.isBlank()) return ""
        return securityManager.encryptDataLegacyCompat(totpPayload)
    }

    /**
     * Bitwarden 密码需要支持“服务器暂时不可用时的本地查看”。
     * 若主路径加密后的密文当前无法立即读回，则降级为兼容密文，避免详情页出现空密码。
     */
    private fun encryptBitwardenPasswordForOfflineDisplay(
        plainPassword: String,
        cipherId: String
    ): String {
        val primaryEncrypted = securityManager.encryptData(plainPassword)
        val primaryReadable = runCatching { securityManager.decryptData(primaryEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false
        if (primaryReadable) {
            return primaryEncrypted
        }

        android.util.Log.w(
            TAG,
            "Bitwarden password payload is not immediately readable; fallback to legacy V1, cipherId=$cipherId"
        )

        val legacyEncrypted = securityManager.encryptDataLegacyCompat(plainPassword)
        val legacyReadable = runCatching { securityManager.decryptData(legacyEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false

        return if (legacyReadable) {
            legacyEncrypted
        } else {
            android.util.Log.w(
                TAG,
                "Legacy fallback is still unreadable; keep primary encrypted payload, cipherId=$cipherId"
            )
            primaryEncrypted
        }
    }
}

@Serializable
private data class SyncFullRawSummary(
    val schemaVersion: Int = 1,
    val rawResponseOmitted: Boolean = true,
    val reason: String = "sync_full success response can be very large; per-cipher snapshots are captured separately",
    val profileIdDigest: String? = null,
    val emailDigest: String? = null,
    val premium: Boolean = false,
    val folderCount: Int = 0,
    val cipherCount: Int = 0,
    val activeCipherCount: Int = 0,
    val collectionCount: Int = 0,
    val policyCount: Int = 0,
    val sendCount: Int = 0
)

// ========== 同步结果 ==========

sealed class SyncResult {
    data class Success(
        val foldersAdded: Int,
        val ciphersAdded: Int,
        val ciphersUpdated: Int,
        val conflictsDetected: Int,
        val skippedDueToLocalDirty: Int
    ) : SyncResult()
    
    data class Error(val message: String) : SyncResult()
    
    /**
     * 空 Vault 保护阻止了同步
     */
    data class EmptyVaultBlocked(
        val localCount: Int,
        val serverCount: Int,
        val reason: String
    ) : SyncResult()
}

sealed class CipherSyncResult {
    object Added : CipherSyncResult()
    object Updated : CipherSyncResult()
    object Conflict : CipherSyncResult()
    data class Skipped(val reason: String) : CipherSyncResult()
    data class Error(val message: String) : CipherSyncResult()
}

sealed class UploadResult {
    data class Success(
        val uploaded: Int,
        val failed: Int,
        val authRequiredFailures: Int = 0
    ) : UploadResult()
    data class Error(val message: String) : UploadResult()
}

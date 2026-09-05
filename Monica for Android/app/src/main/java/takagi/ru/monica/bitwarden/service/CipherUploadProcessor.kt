package takagi.ru.monica.bitwarden.service

import android.util.Base64
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.*
import takagi.ru.monica.bitwarden.sync.SyncItemType
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.DocumentType
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.util.TotpDataResolver
import java.util.Date
import java.security.KeyStore
import java.security.MessageDigest

/**
 * 多类型 Cipher 上传处理器
 * 
 * 负责将本地创建的各类条目上传到 Bitwarden 服务器
 * 支持所有类型：Password, TOTP, Card, Note, Document, Passkey
 */
class CipherUploadProcessor(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    companion object {
        private const val TAG = "CipherUploadProcessor"
        private const val CARD_FACE_FIELD_NAME = "Monica Card Face"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private val CIPHER_STRING_PATTERN =
            Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")
        private val LEGACY_MONICA_FIELD_NAMES = setOf(
            "monicalocalid",
            "monicasecureitemid",
            "monicaitemtype",
            "monicaitemdata",
            "monicaimagepaths",
            "monicaisfavorite"
        )
        private val LEGACY_CARD_FIELD_NAMES = setOf(
            "monica_bank_name",
            "monica_card_type",
            "monica_billing_address",
            "monica_nickname",
            "monica_valid_from_month",
            "monica_valid_from_year",
            "monica_pin",
            "monica_iban",
            "monica_swift_bic",
            "monica_routing_number",
            "monica_account_number",
            "monica_branch_code",
            "monica_currency",
            "monica_customer_service_phone"
        )
        private val READABLE_CARD_FIELD_NAMES = setOf(
            "bank name",
            "card type",
            "billing address",
            "nickname",
            "valid from month",
            "valid from year",
            "pin",
            "iban",
            "swift/bic",
            "routing number",
            "account number",
            "branch code",
            "currency",
            "customer service phone",
            "monica card face",
            "monica_card_face"
        )
        private val DOCUMENT_RESERVED_FIELD_NAMES = setOf(
            "monica_document_type",
            "monica_issue_date",
            "monica_expiry_date",
            "monica_issued_by",
            "monica_nationality",
            "monica_additional_info",
            "monica card face",
            "monica_card_face"
        )
    }
    
    private val database = PasswordDatabase.getDatabase(context)
    private val passwordEntryDao = database.passwordEntryDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val securityManager = SecurityManager(context.applicationContext)
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    init {
        runCatching { OperationLogger.init(context.applicationContext) }
    }
    
    /**
     * 上传单个 SecureItem 到 Bitwarden
     */
    suspend fun uploadSecureItem(
        vault: BitwardenVault,
        item: SecureItem,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            val request = when (item.itemType) {
                ItemType.TOTP -> createTotpCipherRequest(item, symmetricKey)
                ItemType.BANK_CARD -> createCardCipherRequest(item, symmetricKey)
                ItemType.NOTE -> createSecureNoteCipherRequest(item, symmetricKey)
                ItemType.DOCUMENT -> createIdentityCipherRequest(item, symmetricKey)
                else -> return UploadItemResult.Error("Unsupported item type: ${item.itemType}")
            }
            val requestPayload = runCatching { json.encodeToString(request) }.getOrNull()
            
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = request
            )
            
            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatching { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "create cipher failed: ${response.code()}"
                )
                return UploadItemResult.Error("Create cipher failed: ${response.code()}")
            }
            
            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                return UploadItemResult.Error("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )
            
            val cardFaceAttachmentResult = syncManagedCardFaceAttachment(
                vault = vault,
                item = item,
                cipher = createdCipher,
                accessToken = accessToken,
                vaultKey = symmetricKey
            )
            val updatedItem = item.copy(
                bitwardenCipherId = createdCipher.id,
                bitwardenRevisionDate = createdCipher.revisionDate,
                bitwardenLocalModified = cardFaceAttachmentResult.isFailure,
                syncStatus = if (cardFaceAttachmentResult.isSuccess) "SYNCED" else "FAILED",
                updatedAt = Date()
            )
            secureItemDao.update(updatedItem)

            if (cardFaceAttachmentResult.isFailure) {
                return UploadItemResult.Error("Card face attachment upload failed")
            }
            
            android.util.Log.d(TAG, "Uploaded SecureItem ${item.id} as cipher ${createdCipher.id}")
            UploadItemResult.Success(createdCipher.id)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Upload SecureItem failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 更新单个 SecureItem 到 Bitwarden
     */
    suspend fun updateSecureItem(
        vault: BitwardenVault,
        item: SecureItem,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            val request = when (item.itemType) {
                ItemType.TOTP -> createTotpCipherRequest(item, symmetricKey)
                ItemType.BANK_CARD -> createCardCipherRequest(item, symmetricKey)
                ItemType.NOTE -> createSecureNoteCipherRequest(item, symmetricKey)
                ItemType.DOCUMENT -> createIdentityCipherRequest(item, symmetricKey)
                else -> return UploadItemResult.Error("Unsupported item type: ${item.itemType}")
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val baselineCipher = fetchCipherForFieldMerge(vaultApi, accessToken, cipherId)
            val mergedRequest = mergeRequestWithCipherBaseline(request, baselineCipher, symmetricKey)
            val updateRequest = mergedRequest.toUpdateRequest()
            val requestPayload = runCatching { json.encodeToString(updateRequest) }.getOrNull()
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatching { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                return UploadItemResult.Error("Update cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                return UploadItemResult.Error("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )

            val cardFaceAttachmentResult = syncManagedCardFaceAttachment(
                vault = vault,
                item = item,
                cipher = updatedCipher,
                accessToken = accessToken,
                vaultKey = symmetricKey
            )
            val updatedItem = item.copy(
                bitwardenRevisionDate = updatedCipher.revisionDate,
                bitwardenLocalModified = cardFaceAttachmentResult.isFailure,
                syncStatus = if (cardFaceAttachmentResult.isSuccess) "SYNCED" else "FAILED",
                updatedAt = Date()
            )
            secureItemDao.update(updatedItem)

            if (cardFaceAttachmentResult.isFailure) {
                return UploadItemResult.Error("Card face attachment upload failed")
            }

            logBitwardenSecureItemEditHistory(
                vaultId = vault.id,
                item = item,
                cipherId = cipherId,
                baselineCipher = baselineCipher,
                updateRequest = updateRequest,
                symmetricKey = symmetricKey
            )
            UploadItemResult.Success(updatedCipher.id)
        } catch (e: IllegalArgumentException) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "invalid payload"
            )
            android.util.Log.w(
                TAG,
                "Skip SecureItem update to avoid payload pollution: ${e.message}"
            )
            UploadItemResult.Error(e.message ?: "Invalid secure item payload")
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Update SecureItem failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun syncManagedCardFaceAttachment(
        vault: BitwardenVault,
        item: SecureItem,
        cipher: CipherApiResponse,
        accessToken: String,
        vaultKey: SymmetricCryptoKey
    ): Result<Unit> {
        if (item.itemType != ItemType.BANK_CARD && item.itemType != ItemType.DOCUMENT) {
            return Result.success(Unit)
        }
        val fileName = when (item.itemType) {
            ItemType.BANK_CARD -> parseBankCardData(item).cardFace?.imageAttachmentName
            ItemType.DOCUMENT -> parseDocumentData(item).cardFace?.imageAttachmentName
            else -> null
        }
            ?.takeIf(CardFaceAttachment::isManagedFileName)
        val facade = AttachmentContainer.facade(context.applicationContext)
        val owner = AttachmentOwner.secureItem(item.id)
        val managedAttachments = facade.list(owner).filter { attachment ->
            CardFaceAttachment.isManagedFileName(attachment.fileName)
        }
        if (managedAttachments.isEmpty()) return Result.success(Unit)
        val hasPendingLocalImage = fileName != null && managedAttachments.any { attachment ->
            attachment.sourceEnum == AttachmentSource.LOCAL && attachment.fileName == fileName
        }
        if (hasPendingLocalImage && !BitwardenVaultPremiumStore.isPremium(context, vault.id)) {
            return Result.failure(IllegalStateException("Bitwarden attachment entitlement unavailable"))
        }

        return runCatching {
            BitwardenCipherKeyResolver.withCipherKey(
                cipher = cipher,
                vaultKey = vaultKey,
                logTag = TAG
            ) { effectiveKey ->
                val targetContext = AttachmentFacade.BitwardenContext(
                    vaultApi = apiManager.getVaultApi(vault),
                    httpClient = apiManager.getOkHttpClient(vault),
                    accessToken = accessToken,
                    cipherId = cipher.id,
                    wrappingKey = effectiveKey,
                    isOnline = true
                )
                if (hasPendingLocalImage) {
                    facade.promoteLocalAttachmentsToBitwarden(
                        owner = owner,
                        targetContext = targetContext,
                        fileNames = setOf(requireNotNull(fileName))
                    )
                }
                facade.list(owner)
                    .filter { attachment ->
                        CardFaceAttachment.isManagedFileName(attachment.fileName) &&
                            (fileName == null || attachment.fileName != fileName)
                    }
                    .forEach { stale ->
                        facade.deleteAttachment(
                            attachmentId = stale.id,
                            bitwardenContext = targetContext
                        )
                    }
            }
        }
    }
    
    /**
     * 上传 Passkey 元数据到 Bitwarden
     */
    suspend fun uploadPasskey(
        vault: BitwardenVault,
        passkey: PasskeyEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            if (!canSyncPasskeyToBitwarden(passkey)) {
                return fail("Legacy passkey cannot be synced to Bitwarden")
            }

            val normalizedPasskey = normalizePasskeyForUpload(passkey)
            val mapper = PasskeyMapper()
            val request = mapper.toCreateRequest(normalizedPasskey, normalizedPasskey.bitwardenFolderId)
            if (request.login?.fido2Credentials.isNullOrEmpty()) {
                return fail(
                    "Passkey key material is missing or invalid; cannot sync as FIDO2 credential"
                )
            }
            
            // 加密请求
            val encryptedRequest = encryptCipherRequest(request, symmetricKey)
            val requestPayload = runCatching { json.encodeToString(encryptedRequest) }.getOrNull()
            
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = encryptedRequest
            )
            
            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatching { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "create cipher failed: ${response.code()}"
                )
                return fail("Create cipher failed: ${response.code()}")
            }
            
            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )
            if (createdCipher.login?.fido2Credentials.isNullOrEmpty()) {
                return fail("Server created cipher without FIDO2 credential")
            }
            
            // 更新本地 Passkey
            passkeyDao.markSyncedByRecordId(passkey.id, createdCipher.id)
            
            android.util.Log.d(TAG, "Uploaded Passkey as cipher")
            UploadItemResult.Success(createdCipher.id)
        } catch (e: Exception) {
            runCatching { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Upload Passkey failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 更新已存在的 Passkey Cipher（用于修复历史兼容字段）
     */
    suspend fun updatePasskey(
        vault: BitwardenVault,
        passkey: PasskeyEntry,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            if (!canSyncPasskeyToBitwarden(passkey)) {
                return fail("Legacy passkey cannot be synced to Bitwarden")
            }

            val normalizedPasskey = normalizePasskeyForUpload(passkey)
            val mapper = PasskeyMapper()
            val createRequest = mapper.toCreateRequest(normalizedPasskey, normalizedPasskey.bitwardenFolderId)
            if (createRequest.login?.fido2Credentials.isNullOrEmpty()) {
                return fail(
                    "Passkey key material is missing or invalid; cannot sync as FIDO2 credential"
                )
            }

            val encryptedCreate = encryptCipherRequest(createRequest, symmetricKey)
            val updateRequest = encryptedCreate.toUpdateRequest()
            val requestPayload = runCatching { json.encodeToString(updateRequest) }.getOrNull()

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatching { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                return fail("Update cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatching { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )
            if (updatedCipher.login?.fido2Credentials.isNullOrEmpty()) {
                return fail("Server updated cipher without FIDO2 credential")
            }

            passkeyDao.markSyncedByRecordId(passkey.id, updatedCipher.id)
            UploadItemResult.Success(updatedCipher.id)
        } catch (e: Exception) {
            runCatching { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Update Passkey failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun normalizePasskeyForUpload(passkey: PasskeyEntry): PasskeyEntry {
        val normalizedKey = PasskeyPrivateKeyStore.normalizeForBitwardenUpload(
            context = context,
            keyReferenceOrMaterial = passkey.privateKeyAlias
        )
            ?: return passkey
        return if (normalizedKey == passkey.privateKeyAlias) {
            passkey
        } else {
            passkey.copy(privateKeyAlias = normalizedKey)
        }
    }
    
    /**
     * 批量上传待同步的 SecureItems
     */
    suspend fun uploadPendingSecureItems(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val pending = secureItemDao.getLocalEntriesPendingUpload(vault.id)
        
        if (pending.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }
        
        var uploaded = 0
        var failed = 0
        
        for (item in pending) {
            val result = uploadSecureItem(vault, item, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }
        
        return BatchUploadResult(uploaded = uploaded, failed = failed, total = pending.size)
    }

    /**
     * 批量上传已修改的 SecureItems（已有 cipherId）
     */
    suspend fun uploadModifiedSecureItems(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val modifiedItems = secureItemDao.getLocalModifiedEntries(vault.id)
            .filter { !it.bitwardenCipherId.isNullOrBlank() }

        if (modifiedItems.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }

        var uploaded = 0
        var failed = 0

        for (item in modifiedItems) {
            val cipherId = item.bitwardenCipherId
            if (cipherId.isNullOrBlank()) {
                failed++
                continue
            }
            val result = updateSecureItem(vault, item, cipherId, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = modifiedItems.size)
    }
    
    /**
     * 批量上传待同步的 Passkeys
     */
    suspend fun uploadPendingPasskeys(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val pending = passkeyDao.getLocalEntriesPendingUpload(vault.id)
            .filter(::canSyncPasskeyToBitwarden)
            .toMutableList()
        val vaultPasswordIds = passwordEntryDao.getEntriesByVaultId(vault.id).map { it.id }
        if (vaultPasswordIds.isNotEmpty()) {
            val boundCandidates = passkeyDao.getByBoundPasswordIds(vaultPasswordIds)
                .filter { passkey ->
                    canSyncPasskeyToBitwarden(passkey) &&
                    passkey.syncStatus != "REFERENCE" &&
                        passkey.bitwardenCipherId.isNullOrBlank() &&
                        passkey.bitwardenVaultId != vault.id
                }

            boundCandidates.forEach { candidate ->
                val reassigned = candidate.copy(
                    bitwardenVaultId = vault.id,
                    syncStatus = "PENDING"
                )
                passkeyDao.update(reassigned)
                pending.add(reassigned)
            }
        }

        val uniquePending = pending.distinctBy { passkey ->
            passkey.id.takeIf { it > 0L }?.let { recordId ->
                "record:$recordId"
            } ?: listOf(
                passkey.credentialId,
                passkey.rpId,
                passkey.userName,
                passkey.userDisplayName,
                passkey.boundPasswordId?.toString().orEmpty(),
                passkey.privateKeyAlias,
                passkey.bitwardenCipherId.orEmpty()
            ).joinToString("|")
        }
        if (uniquePending.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }
        
        var uploaded = 0
        var failed = 0
        
        for (passkey in uniquePending) {
            val result = uploadPasskey(vault, passkey, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = uniquePending.size)
    }

    /**
     * 批量更新已同步的 Passkeys（修复 counter / userHandle 等字段）
     */
    suspend fun uploadModifiedPasskeys(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val candidates = passkeyDao.getByBitwardenVaultId(vault.id)
            .filter { passkey ->
                canSyncPasskeyToBitwarden(passkey) &&
                passkey.syncStatus != "REFERENCE" &&
                    (passkey.syncStatus == "PENDING" || passkey.syncStatus == "FAILED") &&
                    !passkey.bitwardenCipherId.isNullOrBlank() &&
                    passkey.privateKeyAlias.isNotBlank()
            }

        if (candidates.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }

        var uploaded = 0
        var failed = 0

        for (passkey in candidates) {
            val cipherId = passkey.bitwardenCipherId
            if (cipherId.isNullOrBlank()) {
                failed++
                continue
            }
            val result = updatePasskey(vault, passkey, cipherId, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = candidates.size)
    }

    private fun canSyncPasskeyToBitwarden(passkey: PasskeyEntry): Boolean {
        return passkey.passkeyMode == PasskeyEntry.MODE_BW_COMPAT
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

    private fun logBitwardenSecureItemEditHistory(
        vaultId: Long,
        item: SecureItem,
        cipherId: String,
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ) {
        val changes = buildSecureItemEditHistoryChanges(
            baselineCipher = baselineCipher,
            updateRequest = updateRequest,
            symmetricKey = symmetricKey
        )
        if (changes.isEmpty()) return

        OperationLogger.logUpdate(
            itemType = OperationLogItemType.BITWARDEN_SYNC,
            itemId = buildBitwardenItemId(vaultId, cipherId),
            itemTitle = item.title.ifBlank { "Bitwarden Secure Item" },
            changes = changes
        )
    }

    private fun buildSecureItemEditHistoryChanges(
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
            fieldName = "notes",
            oldValue = decryptOrPlain(baselineCipher?.notes, symmetricKey),
            newValue = decryptOrPlain(updateRequest.notes, symmetricKey),
            sensitive = true
        )

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

        appendIfChanged(
            changes = changes,
            fieldName = "username",
            oldValue = decryptOrPlain(baselineCipher?.login?.username, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.username, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "totp",
            oldValue = decryptOrPlain(baselineCipher?.login?.totp, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.totp, symmetricKey),
            sensitive = true
        )

        val oldUriCount = baselineCipher?.login?.uris?.size ?: 0
        val newUriCount = updateRequest.login?.uris?.size ?: 0
        if (oldUriCount != newUriCount) {
            changes += FieldChange("login_uri_count", oldUriCount.toString(), newUriCount.toString())
        }

        appendIfChanged(
            changes = changes,
            fieldName = "cardholder",
            oldValue = decryptOrPlain(baselineCipher?.card?.cardholderName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.cardholderName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_number",
            oldValue = decryptOrPlain(baselineCipher?.card?.number, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.number, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_exp_month",
            oldValue = decryptOrPlain(baselineCipher?.card?.expMonth, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.expMonth, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_exp_year",
            oldValue = decryptOrPlain(baselineCipher?.card?.expYear, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.expYear, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_cvv",
            oldValue = decryptOrPlain(baselineCipher?.card?.code, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.code, symmetricKey),
            sensitive = true
        )

        appendIfChanged(
            changes = changes,
            fieldName = "identity_first_name",
            oldValue = decryptOrPlain(baselineCipher?.identity?.firstName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.firstName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_last_name",
            oldValue = decryptOrPlain(baselineCipher?.identity?.lastName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.lastName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_email",
            oldValue = decryptOrPlain(baselineCipher?.identity?.email, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.email, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_phone",
            oldValue = decryptOrPlain(baselineCipher?.identity?.phone, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.phone, symmetricKey),
            sensitive = true
        )

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
    
    // ========== 创建各类型 Cipher 请求 ==========
    
    private fun createTotpCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val totpData = parseTotpData(item)
        val normalizedTotp = TotpDataResolver.normalizeTotpData(
            TotpData(
                secret = totpData.secret,
                issuer = totpData.issuer,
                accountName = totpData.account,
                algorithm = totpData.algorithm,
                digits = totpData.digits,
                period = totpData.period,
                otpType = OtpType.TOTP
            )
        )
        val totpPayload = TotpDataResolver.toBitwardenPayload(item.title, normalizedTotp)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 1,  // Login with TOTP
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            login = CipherLoginApiData(
                username = normalizedTotp.accountName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                totp = crypto.encryptString(totpPayload, symmetricKey),
                uris = normalizedTotp.issuer.takeIf { it.isNotBlank() }?.let {
                    listOf(CipherUriApiData(uri = crypto.encryptString("otpauth://totp/$it", symmetricKey)))
                }
            )
        )
    }

    private fun createCardCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val cardData = parseBankCardData(item)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 3,  // Card
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            card = CipherCardApiData(
                cardholderName = cardData.cardholderName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                number = cardData.cardNumber.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                expMonth = cardData.expiryMonth.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                expYear = cardData.expiryYear.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                code = cardData.cvv.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                brand = cardData.brand.ifBlank { cardData.bankName }.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                }
            ),
            fields = buildEncryptedCardFields(cardData, symmetricKey)
        )
    }
    
    private fun createSecureNoteCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val noteData = parseNoteData(item)
        val externalContent = NoteContentCodec.toExternalReadableContent(noteData.content)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 2,  // SecureNote
            name = crypto.encryptString(item.title, symmetricKey),
            notes = crypto.encryptString(externalContent, symmetricKey),
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            secureNote = CipherSecureNoteApiData(type = 0),
            fields = buildEncryptedFields(
                symmetricKey = symmetricKey,
                reservedFields = emptyList(),
                customFields = noteData.customFields
            )
        )
    }
    
    private fun createIdentityCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val docData = parseDocumentData(item)
        
        val crypto = BitwardenCrypto
        val identityNumberForLicense = docData.licenseNumber.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && docData.documentType == DocumentType.DRIVER_LICENSE
            }.orEmpty()
        }
        val identityNumberForPassport = docData.passportNumber.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && docData.documentType == DocumentType.PASSPORT
            }.orEmpty()
        }
        val identityNumberForSsn = docData.ssn.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && (
                    docData.documentType == DocumentType.ID_CARD ||
                        docData.documentType == DocumentType.SOCIAL_SECURITY ||
                        docData.documentType == DocumentType.OTHER
                    )
            }.orEmpty()
        }
        
        return CipherCreateRequest(
            type = 4,  // Identity
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            identity = CipherIdentityApiData(
                title = docData.title.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                firstName = docData.firstName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                middleName = docData.middleName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                lastName = docData.lastName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address1 = docData.address1.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address2 = docData.address2.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address3 = docData.address3.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                city = docData.city.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                state = docData.stateProvince.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                postalCode = docData.postalCode.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                country = docData.country.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                company = docData.company.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                email = docData.email.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                phone = docData.phone.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                ssn = identityNumberForSsn.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                username = docData.username.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                passportNumber = identityNumberForPassport.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                licenseNumber = identityNumberForLicense.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                }
            ),
            fields = buildEncryptedDocumentFields(docData, symmetricKey)
        )
    }

    private fun parseTotpData(item: SecureItem): TotpItemData {
        return try {
            val appData = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            ) ?: throw IllegalArgumentException("Unable to parse Monica TOTP payload")
            TotpItemData(
                secret = appData.secret,
                issuer = appData.issuer,
                account = appData.accountName,
                algorithm = appData.algorithm,
                digits = appData.digits,
                period = appData.period
            )
        } catch (_: Exception) {
            try {
                json.decodeFromString(TotpItemData.serializer(), item.itemData)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "Unsupported TOTP payload for SecureItem#${item.id}; update skipped to prevent data loss"
                )
            }
        }
    }

    private fun parseBankCardData(item: SecureItem): BankCardData {
        return CardWalletDataCodec.parseBankCardData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
            ?: throw IllegalArgumentException(
                "Unsupported BANK_CARD payload for SecureItem#${item.id}; update skipped to prevent data loss"
            )
    }

    private fun parseNoteData(item: SecureItem): NoteItemData {
        return try {
            val appData = json.decodeFromString<NoteData>(item.itemData)
            NoteItemData(
                content = appData.content,
                isMarkdown = appData.isMarkdown,
                tags = appData.tags,
                customFields = appData.customFields
            )
        } catch (_: Exception) {
            try {
                json.decodeFromString(NoteItemData.serializer(), item.itemData)
            } catch (_: Exception) {
                if (item.itemData.isBlank() && item.notes.isNotBlank()) {
                    NoteItemData(content = item.notes)
                } else {
                    throw IllegalArgumentException(
                        "Unsupported NOTE payload for SecureItem#${item.id}; update skipped to prevent data loss"
                    )
                }
            }
        }
    }

    private fun parseDocumentData(item: SecureItem): DocumentData {
        return CardWalletDataCodec.parseDocumentData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
            ?: throw IllegalArgumentException(
                "Unsupported DOCUMENT payload for SecureItem#${item.id}; update skipped to prevent data loss"
            )
    }

    private suspend fun fetchCipherForFieldMerge(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String
    ): CipherApiResponse? {
        return try {
            val response = vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
            if (response.isSuccessful) {
                response.body()
            } else {
                android.util.Log.w(
                    TAG,
                    "Fetch cipher baseline failed for merge, cipherId=$cipherId, code=${response.code()}"
                )
                null
            }
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "Fetch cipher baseline exception for merge, cipherId=$cipherId, error=${e.message}"
            )
            null
        }
    }

    private fun mergeRequestWithCipherBaseline(
        request: CipherCreateRequest,
        baselineCipher: CipherApiResponse?,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        if (baselineCipher == null) return request

        val mergedFields = mergeCipherFields(
            localFields = request.fields,
            serverFields = baselineCipher.fields,
            symmetricKey = symmetricKey,
            removeCardStructuredFields = request.type == 3
        )

        return request.copy(fields = mergedFields)
    }

    private fun mergeCipherFields(
        localFields: List<CipherFieldApiData>?,
        serverFields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey,
        removeCardStructuredFields: Boolean
    ): List<CipherFieldApiData>? {
        if (serverFields.isNullOrEmpty()) return localFields

        val merged = localFields.orEmpty().toMutableList()
        val fieldKeys = localFields.orEmpty()
            .map { buildFieldMergeKey(it, symmetricKey) }
            .toMutableSet()

        serverFields
            .filterNot { serverField ->
                removeCardStructuredFields && isInternalOrStructuredCardFieldName(
                    decryptOrPlain(serverField.name, symmetricKey).orEmpty()
                )
            }
            .forEach { serverField ->
            val serverKey = buildFieldMergeKey(serverField, symmetricKey)
            if (fieldKeys.add(serverKey)) {
                merged += serverField
            }
        }

        return merged.ifEmpty { null }
    }

    private fun buildFieldMergeKey(
        field: CipherFieldApiData,
        symmetricKey: SymmetricCryptoKey
    ): String {
        val plainName = decryptOrPlain(field.name, symmetricKey)
            ?.trim()
            .orEmpty()
        return if (plainName.isBlank()) {
            "opaque|${field.type}|${field.linkedId}|${field.name.orEmpty()}|${field.value.orEmpty()}"
        } else {
            "named|${field.type}|${field.linkedId}|$plainName"
        }
    }

    private fun isInternalOrStructuredCardFieldName(name: String): Boolean {
        val normalized = normalizeFieldName(name)
        return normalized in LEGACY_MONICA_FIELD_NAMES ||
            normalized in LEGACY_CARD_FIELD_NAMES ||
            normalized in READABLE_CARD_FIELD_NAMES
    }

    private fun normalizeFieldName(name: String): String {
        return name.trim().lowercase()
    }

    private fun decryptOrPlain(value: String?, symmetricKey: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return value
        if (!CIPHER_STRING_PATTERN.matches(value)) return value
        return runCatching {
            BitwardenCrypto.decryptToString(value, symmetricKey)
        }.getOrNull()
    }

    private fun buildEncryptedDocumentFields(
        docData: DocumentData,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        val reserved = buildList {
            add("monica_document_type" to docData.documentType.name)
            add("monica_issue_date" to docData.issuedDate)
            add("monica_expiry_date" to docData.expiryDate)
            add("monica_issued_by" to docData.issuedBy)
            add("monica_nationality" to docData.nationality)
            add("monica_additional_info" to docData.additionalInfo)
            docData.cardFace?.let { config ->
                add(CARD_FACE_FIELD_NAME to CardWalletDataCodec.encodeCardFaceConfig(config))
            }
        }
        return buildEncryptedFields(
            symmetricKey = symmetricKey,
            reservedFields = reserved,
            customFields = docData.customFields
                .filterNot { field -> field.label.trim().lowercase() in DOCUMENT_RESERVED_FIELD_NAMES }
        )
    }

    private fun buildEncryptedCardFields(
        cardData: BankCardData,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        val billingAddressDisplay = CardWalletDataCodec.parseBillingAddress(cardData.billingAddress)
            .formatForDisplay()
            .ifBlank { cardData.billingAddress }
        val reserved = buildList {
            add("Bank Name" to cardData.bankName)
            add("Card Type" to cardData.cardType.name)
            add("Billing Address" to billingAddressDisplay)
            add("Nickname" to cardData.nickname)
            add("Valid From Month" to cardData.validFromMonth)
            add("Valid From Year" to cardData.validFromYear)
            add("PIN" to cardData.pin)
            add("IBAN" to cardData.iban)
            add("SWIFT/BIC" to cardData.swiftBic)
            add("Routing Number" to cardData.routingNumber)
            add("Account Number" to cardData.accountNumber)
            add("Branch Code" to cardData.branchCode)
            add("Currency" to cardData.currency)
            add("Customer Service Phone" to cardData.customerServicePhone)
            cardData.cardFace?.let { config ->
                add(CARD_FACE_FIELD_NAME to CardWalletDataCodec.encodeCardFaceConfig(config))
            }
        }
        return buildEncryptedFields(
            symmetricKey = symmetricKey,
            reservedFields = reserved,
            customFields = cardData.customFields,
            excludedCustomFieldNames = LEGACY_MONICA_FIELD_NAMES + LEGACY_CARD_FIELD_NAMES + READABLE_CARD_FIELD_NAMES
        )
    }

    private fun buildEncryptedFields(
        symmetricKey: SymmetricCryptoKey,
        reservedFields: List<Pair<String, String>>,
        customFields: List<SecureCustomField>,
        excludedCustomFieldNames: Set<String> = emptySet()
    ): List<CipherFieldApiData>? {
        val crypto = BitwardenCrypto
        val result = mutableListOf<CipherFieldApiData>()

        reservedFields
            .filter { (_, value) -> value.isNotBlank() }
            .forEach { (name, value) ->
                result += CipherFieldApiData(
                    name = crypto.encryptString(name, symmetricKey),
                    value = crypto.encryptString(value, symmetricKey),
                    type = 0
                )
            }

        customFields
            .filter { it.isValid() }
            .filterNot { normalizeFieldName(it.label) in excludedCustomFieldNames }
            .forEach { field ->
                result += CipherFieldApiData(
                    name = crypto.encryptString(field.label, symmetricKey),
                    value = crypto.encryptString(field.value, symmetricKey),
                    type = when (field.type) {
                        SecureCustomFieldType.TEXT -> 0
                        SecureCustomFieldType.HIDDEN -> 1
                        SecureCustomFieldType.BOOLEAN -> 2
                    }
                )
            }

        return result.ifEmpty { null }
    }

    private fun CipherCreateRequest.toUpdateRequest(): CipherUpdateRequest {
        return CipherUpdateRequest(
            type = type,
            folderId = folderId,
            name = name,
            notes = notes,
            login = login,
            card = card,
            identity = identity,
            secureNote = secureNote,
            sshKey = sshKey,
            fields = fields,
            favorite = favorite,
            reprompt = reprompt,
            archivedDate = archivedDate
        )
    }
    
    /**
     * 加密 CipherCreateRequest
     */
    private fun encryptCipherRequest(
        request: CipherCreateRequest,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val crypto = BitwardenCrypto
        
        fun isEncrypted(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            if (!CIPHER_STRING_PATTERN.matches(value)) return false
            return runCatching { crypto.parseCipherString(value) }.isSuccess
        }

        fun encryptIfNeeded(value: String?): String? {
            if (value.isNullOrBlank()) return value
            return if (isEncrypted(value)) value else crypto.encryptString(value, symmetricKey)
        }
        
        return request.copy(
            name = encryptIfNeeded(request.name) ?: request.name,
            notes = encryptIfNeeded(request.notes),
            login = request.login?.let { login ->
                login.copy(
                    username = encryptIfNeeded(login.username),
                    password = encryptIfNeeded(login.password),
                    totp = encryptIfNeeded(login.totp),
                    uris = login.uris?.map { uri ->
                        uri.copy(
                            uri = encryptIfNeeded(uri.uri)
                        )
                    },
                    fido2Credentials = login.fido2Credentials?.map { fido ->
                        fido.copy(
                            credentialId = encryptIfNeeded(fido.credentialId),
                            keyType = encryptIfNeeded(fido.keyType),
                            keyAlgorithm = encryptIfNeeded(fido.keyAlgorithm),
                            keyCurve = encryptIfNeeded(fido.keyCurve),
                            keyValue = encryptIfNeeded(fido.keyValue),
                            rpId = encryptIfNeeded(fido.rpId),
                            rpName = encryptIfNeeded(fido.rpName),
                            counter = encryptIfNeeded(fido.counter),
                            userHandle = encryptIfNeeded(fido.userHandle),
                            userName = encryptIfNeeded(fido.userName),
                            userDisplayName = encryptIfNeeded(fido.userDisplayName),
                            discoverable = encryptIfNeeded(fido.discoverable),
                            // Bitwarden expects a parseable DateTime here, not a cipher string.
                            creationDate = fido.creationDate
                        )
                    },
                )
            },
            sshKey = request.sshKey?.let { sshKey ->
                sshKey.copy(
                    privateKey = encryptIfNeeded(sshKey.privateKey),
                    publicKey = encryptIfNeeded(sshKey.publicKey),
                    keyFingerprint = encryptIfNeeded(sshKey.keyFingerprint)
                )
            }
        )
    }
}

/**
 * 单项上传结果
 */
sealed class UploadItemResult {
    data class Success(val cipherId: String) : UploadItemResult()
    data class Error(val message: String) : UploadItemResult()
}

/**
 * 批量上传结果
 */
data class BatchUploadResult(
    val uploaded: Int,
    val failed: Int,
    val total: Int
) {
    val success: Boolean get() = failed == 0
}

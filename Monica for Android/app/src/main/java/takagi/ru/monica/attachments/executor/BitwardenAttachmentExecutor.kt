package takagi.ru.monica.attachments.executor

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.model.normalizeAttachmentFailure
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.bitwarden.api.AttachmentUploadRequest
import takagi.ru.monica.bitwarden.api.AttachmentUploadResponse
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

internal fun requireKnownAttachmentUploadType(value: Int?): Int = when (value) {
    0 -> 0
    1 -> 1
    else -> throw AttachmentError.InvalidRemoteData
}

internal fun requireExactAttachmentAllocationId(meta: AttachmentUploadResponse): String =
    meta.attachmentId?.takeIf { it.isNotBlank() } ?: throw AttachmentError.InvalidRemoteData

internal fun resolveBitwardenAttachmentDownloadUrl(
    freshUrl: String?,
    cachedUrl: String?
): String? = freshUrl?.takeIf { it.isNotBlank() }
    ?: cachedUrl?.takeIf { it.isNotBlank() }

internal fun resolveBitwardenAttachmentKey(
    freshKey: String?,
    remoteKey: String?,
    storedKey: String?
): String? = freshKey?.takeIf { it.isNotBlank() }
    ?: remoteKey?.takeIf { it.isNotBlank() }
    ?: storedKey?.takeIf { it.isNotBlank() }

/**
 * Bitwarden 附件读写执行器。
 *
 * 该 executor 是"薄"的：自身不查 Room，不决定 URL，只负责三件事：
 *
 * 1. `upload`：对给定 [InputStream] 按 Bitwarden 附件格式加密并上传到服务端
 *    （fileUploadType 0 → Direct Multipart；fileUploadType 1 → Azure PUT），
 *    返回可直接落库的 [Attachment]（包含本地缓存密文的 wrappedCek）。
 * 2. `download`：把远端附件密文流式拉到本地，用 Bitwarden 附件密钥解码后，
 *    再用 Monica 的 [AttachmentStorage] 格式重新加密存入 Local_Encrypted_Store。
 * 3. `remove`：调用 DELETE 接口。
 *
 * Upstream（Coordinator）要负责传入：
 * - 已经解包好的 `cipherOrUserKey`（用于包裹/解包附件密钥）；
 * - `accessToken`（用于 Authorization）；
 * - `vaultApi`、`httpClient`（由 `BitwardenApiManager` 提供）。
 *
 * 对应 requirements.md Requirement 5.3 / 5.4 / 5.6。
 */
class BitwardenAttachmentExecutor(
    private val context: Context,
    private val storage: AttachmentStorage,
    private val keyVault: AttachmentKeyVault
) {
    private val recoveryStore by lazy { BitwardenAttachmentRecoveryStore(context) }

    data class UploadContext(
        val vaultApi: BitwardenVaultApi,
        val httpClient: OkHttpClient,
        val accessToken: String,
        val cipherId: String,
        /** 用于包裹/解包附件密钥的 cipher 或 user key。 */
        val wrappingKey: SymmetricCryptoKey,
        val wrappingKeyProvider: (suspend () -> SymmetricCryptoKey)? = null,
        /** Select using the original URL, before HttpUrl can normalize its path. */
        val clientForUrl: (String) -> OkHttpClient = { httpClient },
        val requireCurrent: () -> Unit = {},
        val withPublication: suspend (suspend () -> Unit) -> Unit = { it() },
        val recoveryBinding: BitwardenAttachmentRecoveryStore.Binding? = null,
        val localAttachmentId: Long? = null,
        val recoveryOperationId: String? = null,
    )

    /**
     * 把 [source] 的明文字节加密上传为 [cipherId] 的新附件，并在本地写一份缓存密文。
     *
     * 返回的 [Attachment] `id=0`，由调用方写 Room。上传失败时抛出 [AttachmentError]，
     * 本地临时文件会被清理。
     */
    suspend fun upload(
        parentPasswordId: Long,
        fileName: String,
        mimeType: String,
        source: InputStream,
        sizeBytes: Long,
        ctx: UploadContext
    ): Attachment = upload(
        owner = AttachmentOwner.password(parentPasswordId),
        fileName = fileName,
        mimeType = mimeType,
        source = source,
        sizeBytes = sizeBytes,
        ctx = ctx
    )

    suspend fun upload(
        owner: AttachmentOwner,
        fileName: String,
        mimeType: String,
        source: InputStream,
        sizeBytes: Long,
        ctx: UploadContext
    ): Attachment {
        var stagedPath: String? = null
        try {
            return withContext(Dispatchers.IO) {
                ctx.requireCurrent()
                ctx.recoveryBinding?.let { binding ->
                    check(binding.cipherId == ctx.cipherId && binding.ownerKind == owner.kind.name && binding.ownerId == owner.id)
                    val previous = ctx.recoveryOperationId?.let { recoveryStore.load(binding, it) }
                        ?: ctx.localAttachmentId?.let { recoveryStore.findPending(binding, it) }
                    if (previous != null) return@withContext recoverUpload(previous.operationId, ctx)
                }
                val wrappingKey = ctx.wrappingKeyProvider?.invoke()
                    ?: SymmetricCryptoKey(ctx.wrappingKey.encKey.copyOf(), ctx.wrappingKey.macKey.copyOf())
                val (attachmentKey, fileKeyEnc, encryptedFileName) = try {
                    ctx.requireCurrent()
                    val name = BitwardenAttachmentCrypto.encryptStringForAttachment(fileName, wrappingKey)
                    val (key, wrapped) = BitwardenAttachmentCrypto.generateAndWrapAttachmentKey(wrappingKey)
                    Triple(key, wrapped, name)
                } finally {
                    wrappingKey.clear()
                }

                val ciphertextTmp = try {
                    File.createTempFile("bw_att_", ".bin", context.applicationContext.cacheDir)
                } catch (error: Exception) {
                    attachmentKey.clear()
                    throw error
                }
                try {
                    val plainResult = try {
                        BitwardenAttachmentCrypto.encryptFile(source, ciphertextTmp, attachmentKey)
                    } catch (e: Throwable) {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                        throw e
                    }

                    val ciphertextSize = ciphertextTmp.length()
                    val request = AttachmentUploadRequest(fileKeyEnc, encryptedFileName, ciphertextSize.toString())
                    var recovery = ctx.recoveryBinding?.let { binding ->
                        recoveryStore.prepare(binding, ctx.localAttachmentId, fileName, mimeType,
                            plainResult.plainSizeBytes, plainResult.plainSha256Hex, request, ciphertextTmp, ctx.requireCurrent)
                    }

                    // 2. 请求上传 URL
                    ctx.requireCurrent()
                    recovery = recovery?.let {
                        recoveryStore.advance(it.binding, it.operationId, it.phase,
                            BitwardenAttachmentRecoveryStore.Phase.ALLOCATING, requireCurrent = ctx.requireCurrent)
                    }
                    val uploadResp = try {
                        ctx.vaultApi.createAttachmentUploadUrl(
                            authorization = bearer(ctx.accessToken),
                            cipherId = ctx.cipherId,
                            request = request
                        )
                    } catch (e: IOException) {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                        throw AttachmentError.NetworkError(null, e)
                    }
                    ctx.requireCurrent()
                    if (!uploadResp.isSuccessful) {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                        throw AttachmentError.NetworkError(uploadResp.code())
                    }
                    val uploadMeta = uploadResp.body()
                        ?: run {
                            attachmentKey.clear()
                            ciphertextTmp.delete()
                            throw AttachmentError.NetworkError(uploadResp.code())
                        }
                    val attachmentId = requireExactAttachmentAllocationId(uploadMeta)
                    recovery = recovery?.let {
                        recoveryStore.advance(it.binding, it.operationId, it.phase,
                            BitwardenAttachmentRecoveryStore.Phase.ALLOCATED, remoteId = attachmentId,
                            uploadType = uploadMeta.fileUploadType, requireCurrent = ctx.requireCurrent)
                    }
                    val uploadType = requireKnownAttachmentUploadType(uploadMeta.fileUploadType)

                    // 3. 按 fileUploadType 分派上传方式
                    try {
                        recovery = recovery?.let {
                            recoveryStore.advance(it.binding, it.operationId, it.phase,
                                BitwardenAttachmentRecoveryStore.Phase.UPLOADING, requireCurrent = ctx.requireCurrent)
                        }
                        when (uploadType) {
                            FILE_UPLOAD_TYPE_DIRECT -> uploadDirect(
                                ctx = ctx,
                                cipherId = ctx.cipherId,
                                attachmentId = attachmentId,
                                fileKeyEnc = fileKeyEnc,
                                ciphertextFile = ciphertextTmp
                            )
                            1 -> {
                                val url = uploadMeta.url
                                    ?: throw AttachmentError.NetworkError(null)
                                uploadAzure(
                                    ctx = ctx,
                                    url = url,
                                    ciphertextFile = ciphertextTmp
                                )
                            }
                        }
                        recovery = recovery?.let {
                            recoveryStore.advance(it.binding, it.operationId, it.phase,
                                BitwardenAttachmentRecoveryStore.Phase.UPLOADED, requireCurrent = ctx.requireCurrent)
                        }
                    } catch (e: AttachmentError) {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                        throw e
                    } catch (e: IOException) {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                        throw AttachmentError.NetworkError(null, e)
                    }

                    // 4. 用 Monica 的 Local_Encrypted_Store 保存一份本地缓存（便于 offline 预览）
                    ctx.requireCurrent()
                    val localBlob = try {
                        BitwardenAttachmentCrypto.openDecrypted(ciphertextTmp, attachmentKey).use { plainIn ->
                            storage.writeEncrypted(plainIn)
                        }
                    } finally {
                        attachmentKey.clear()
                        ciphertextTmp.delete()
                    }
                    stagedPath = localBlob.relativePath

                    val wrappedLocalCek = try {
                        ctx.requireCurrent()
                        try {
                            keyVault.wrap(localBlob.cek)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw AttachmentError.CryptoError
                        }
                    } finally {
                        localBlob.cek.fill(0)
                    }

                    val now = System.currentTimeMillis()
                    ctx.requireCurrent()
                    Attachment(
                        id = 0,
                        parentPasswordId = owner.passwordId,
                        parentSecureItemId = owner.secureItemId,
                        source = AttachmentSource.BITWARDEN.name,
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = plainResult.plainSizeBytes,
                        sha256Hex = plainResult.plainSha256Hex,
                        wrappedCek = wrappedLocalCek,
                        localPath = localBlob.relativePath,
                        bitwardenAttachmentId = attachmentId,
                        bitwardenUrl = uploadMeta.cipherResponse
                            ?.attachments
                            ?.firstOrNull { it.id == attachmentId }
                            ?.url,
                        bitwardenFileKeyEnc = fileKeyEnc,
                        downloadState = AttachmentDownloadState.DOWNLOADED.name,
                        createdAt = now,
                        updatedAt = now
                    )
                } catch (error: Exception) {
                    throw normalizeAttachmentFailure(error)
                } finally {
                    attachmentKey.clear()
                    ciphertextTmp.delete()
                }
            }
        } catch (error: Throwable) {
            cleanupStagedBlob(stagedPath)
            throw error
        }
    }

    /**
     * 从 [remote] 取字节到本地 Local_Encrypted_Store，返回新 [Attachment] 副本（`id`/时间戳等
     * 由上层保留原记录，一般调用方只需要 `localPath`/`wrappedCek`/`sha256Hex`/`downloadState` 的更新）。
     */
    suspend fun download(
        existing: Attachment,
        remote: CipherAttachmentApiData,
        vaultApi: BitwardenVaultApi,
        httpClient: OkHttpClient,
        accessToken: String,
        cipherId: String,
        wrappingKey: SymmetricCryptoKey,
        wrappingKeyProvider: (suspend () -> SymmetricCryptoKey)? = null,
        clientForUrl: (String) -> OkHttpClient = { httpClient },
        requireCurrent: () -> Unit = {}
    ): Attachment {
        var stagedPath: String? = null
        try {
            return withContext(Dispatchers.IO) {
                requireCurrent()
                // Bitwarden attachment URLs are short-lived. Always ask the cipher endpoint for fresh
                // download metadata first; the URL cached from /sync is only a compatibility fallback.
                var metadataFailure: IOException? = null
                val freshInfoResponse = try {
                    vaultApi.getAttachmentDownload(
                        authorization = bearer(accessToken),
                        cipherId = cipherId,
                        attachmentId = remote.id
                    )
                } catch (error: IOException) {
                    metadataFailure = error
                    null
                }
                requireCurrent()
                val freshInfo = freshInfoResponse
                    ?.takeIf { it.isSuccessful }
                    ?.body()
                if (freshInfo != null && freshInfo.id.isNotBlank() && freshInfo.id != remote.id) {
                    throw AttachmentError.InvalidRemoteData
                }
                val fileKeyEnc = resolveBitwardenAttachmentKey(
                    freshKey = freshInfo?.key,
                    remoteKey = remote.key,
                    storedKey = existing.bitwardenFileKeyEnc
                )
                val downloadUrl = resolveBitwardenAttachmentDownloadUrl(
                    freshUrl = freshInfo?.url,
                    cachedUrl = remote.url
                )
                    ?: throw AttachmentError.NetworkError(freshInfoResponse?.code(), metadataFailure)
                val effectiveKey = wrappingKeyProvider?.invoke()
                    ?: SymmetricCryptoKey(wrappingKey.encKey.copyOf(), wrappingKey.macKey.copyOf())
                val attachmentKey = try {
                    requireCurrent()
                    try {
                        BitwardenAttachmentCrypto.unwrapAttachmentKey(fileKeyEnc, effectiveKey)
                    } catch (error: BitwardenAttachmentCrypto.UnsupportedFormatException) {
                        throw AttachmentError.UnsupportedEncryption(error.encryptionType)
                    } catch (error: Exception) {
                        throw AttachmentError.CryptoError
                    }
                } finally {
                    effectiveKey.clear()
                }

                val ciphertextTmp = try {
                    File.createTempFile("bw_dl_", ".bin", context.applicationContext.cacheDir)
                } catch (error: Exception) {
                    attachmentKey.clear()
                    throw error
                }
                try {
                    requireCurrent()
                    val transferClient = clientForUrl(downloadUrl)
                    requireCurrent()
                    val request = try {
                        Request.Builder().url(downloadUrl).get().build()
                    } catch (_: IllegalArgumentException) {
                        throw AttachmentError.InvalidRemoteData
                    }
                    val response = try {
                        transferClient.newCall(request).execute()
                    } catch (e: IOException) {
                        throw AttachmentError.NetworkError(null, e)
                    }
                    response.use { resp ->
                        requireCurrent()
                        if (!resp.isSuccessful) throw AttachmentError.NetworkError(resp.code)
                        val body = resp.body ?: throw AttachmentError.NetworkError(resp.code)
                        if (body.contentLength() > BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES) {
                            throw AttachmentError.TooLarge(BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES, body.contentLength())
                        }
                        body.byteStream().use { inStream ->
                            ciphertextTmp.outputStream().buffered().use { out ->
                                val buffer = ByteArray(16 * 1024)
                                var received = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    requireCurrent()
                                    val count = try {
                                        inStream.read(buffer)
                                    } catch (error: IOException) {
                                        throw AttachmentError.NetworkError(null, error)
                                    }
                                    if (count < 0) break
                                    if (count == 0) continue
                                    received += count
                                    if (received > BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES) {
                                        throw AttachmentError.TooLarge(BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES, received)
                                    }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                    }

                    // Authenticate first, then stream directly into the local encrypted store.
                    requireCurrent()
                    val blob = BitwardenAttachmentCrypto.openDecrypted(ciphertextTmp, attachmentKey).use { plainIn ->
                        storage.writeEncrypted(plainIn)
                    }
                    stagedPath = blob.relativePath

                    val wrappedLocalCek = try {
                        requireCurrent()
                        try {
                            keyVault.wrap(blob.cek)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw AttachmentError.CryptoError
                        }
                    } finally {
                        blob.cek.fill(0)
                    }

                    requireCurrent()
                    existing.copy(
                        localPath = blob.relativePath,
                        wrappedCek = wrappedLocalCek,
                        sha256Hex = blob.sha256Hex,
                        sizeBytes = blob.sizeBytes,
                        bitwardenUrl = downloadUrl,
                        bitwardenFileKeyEnc = fileKeyEnc,
                        downloadState = AttachmentDownloadState.DOWNLOADED.name,
                        updatedAt = System.currentTimeMillis()
                    )
                } catch (error: Exception) {
                    throw normalizeAttachmentFailure(error)
                } finally {
                    attachmentKey.clear()
                    ciphertextTmp.delete()
                }
            }
        } catch (error: Throwable) {
            cleanupStagedBlob(stagedPath)
            throw error
        }
    }

    /** 调用 DELETE 接口从服务端移除附件；成功返回 true，404 视为已不存在也返回 true。 */
    suspend fun remove(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String,
        bitwardenAttachmentId: String,
        requireCurrent: () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        requireCurrent()
        val resp = try {
            vaultApi.deleteAttachment(
                authorization = bearer(accessToken),
                cipherId = cipherId,
                attachmentId = bitwardenAttachmentId
            )
        } catch (e: IOException) {
            throw AttachmentError.NetworkError(null)
        }
        requireCurrent()
        when (resp.code()) {
            HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT -> true
            HttpURLConnection.HTTP_NOT_FOUND -> true
            else -> throw AttachmentError.NetworkError(resp.code())
        }
    }

    /** Resume an unsent request, or prove a previously uncertain upload by reading its exact bytes.
     * No metadata-only success and no repeat allocation for ALLOCATING/UPLOADING are permitted.
     */
    suspend fun recoverUpload(operationId: String, ctx: UploadContext): Attachment = withContext(Dispatchers.IO) {
        ctx.requireCurrent()
        val binding = requireNotNull(ctx.recoveryBinding)
        var record = recoveryStore.load(binding, operationId)
        check(binding.cipherId == ctx.cipherId)
        check(record.phase !in setOf(BitwardenAttachmentRecoveryStore.Phase.ADOPTED,
            BitwardenAttachmentRecoveryStore.Phase.DELETING, BitwardenAttachmentRecoveryStore.Phase.REMOVED))
        val payload = recoveryStore.ciphertext(binding, operationId)
        if (record.phase == BitwardenAttachmentRecoveryStore.Phase.PREPARED) {
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.ALLOCATING, requireCurrent = ctx.requireCurrent)
            val response = ctx.vaultApi.createAttachmentUploadUrl(bearer(ctx.accessToken), ctx.cipherId, record.request())
            ctx.requireCurrent()
            if (!response.isSuccessful) throw AttachmentError.NetworkError(response.code())
            val meta = response.body() ?: throw AttachmentError.InvalidRemoteData
            val id = requireExactAttachmentAllocationId(meta)
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.ALLOCATED, remoteId = id,
                uploadType = meta.fileUploadType, requireCurrent = ctx.requireCurrent)
            val type = requireKnownAttachmentUploadType(meta.fileUploadType)
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.UPLOADING, requireCurrent = ctx.requireCurrent)
            when (type) {
                0 -> uploadDirect(ctx, ctx.cipherId, id, record.requestKey, payload)
                1 -> uploadAzure(ctx, meta.url ?: throw AttachmentError.InvalidRemoteData, payload)
            }
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.UPLOADED, requireCurrent = ctx.requireCurrent)
            return@withContext materializeRecovery(record, payload, ctx)
        }

        // A random wrapped attachment key plus the exact encrypted name/size binds this request.
        // A missing ID is recoverable only from one complete match, never newest/max/name guesses.
        val cipher = recoveryCipher(ctx)
        val candidates = cipher.attachments.orEmpty().filter {
            BitwardenAttachmentRecoveryStore.matchesRequest(record, it) &&
                (record.remoteId == null || record.remoteId == it.id)
        }
        val remote = candidates.singleOrNull() ?: throw AttachmentError.InvalidRemoteData
        if (record.phase == BitwardenAttachmentRecoveryStore.Phase.ALLOCATED && record.uploadType == 0) {
            // ALLOCATED is durable proof that this operation has not started transferring bytes.
            // It cannot be reached after an uncertain upload or from an arbitrary remote record.
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.UPLOADING, requireCurrent = ctx.requireCurrent)
            uploadDirect(ctx, ctx.cipherId, remote.id, record.requestKey, payload)
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.UPLOADED, requireCurrent = ctx.requireCurrent)
            return@withContext materializeRecovery(record, payload, ctx)
        }

        verifyRemoteRecoveryBytes(record, remote.id, ctx)
        ctx.requireCurrent()
        if (record.phase != BitwardenAttachmentRecoveryStore.Phase.UPLOADED) {
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.UPLOADED, remoteId = remote.id, requireCurrent = ctx.requireCurrent)
        }
        materializeRecovery(record, payload, ctx)
    }

    /** Only an exact reserved slot whose upload never started is automatically reclaimable.
     * The caller checks ALL Room references for this account/cipher/id (including soft-deleted rows)
     * and serializes this operation with its publishers. Uncertain uploads are reconciled, not deleted.
     * REMOVED retains the local encrypted source for a later explicit retry/discard decision.
     */
    suspend fun cleanupRecovery(
        operationId: String, ctx: UploadContext,
        isReferenced: suspend (BitwardenAttachmentRecoveryStore.Record) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val binding = requireNotNull(ctx.recoveryBinding)
        var record = recoveryStore.load(binding, operationId)
        ctx.requireCurrent()
        if (record.phase == BitwardenAttachmentRecoveryStore.Phase.REMOVED) return@withContext true
        if (record.phase !in setOf(BitwardenAttachmentRecoveryStore.Phase.ALLOCATED,
                BitwardenAttachmentRecoveryStore.Phase.DELETING) || isReferenced(record)) return@withContext false
        val remoteId = record.remoteId ?: return@withContext false
        val cipher = recoveryCipher(ctx)
        val attachments = cipher.attachments ?: return@withContext false
        val matches = attachments.filter { it.id == remoteId }
        if (matches.isEmpty()) {
            if (record.phase == BitwardenAttachmentRecoveryStore.Phase.ALLOCATED) {
                record = recoveryStore.advance(binding, operationId, record.phase,
                    BitwardenAttachmentRecoveryStore.Phase.DELETING, requireCurrent = ctx.requireCurrent)
            }
            recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.REMOVED, requireCurrent = ctx.requireCurrent)
            return@withContext true
        }
        if (matches.size != 1 || !BitwardenAttachmentRecoveryStore.matchesRequest(record, matches.single())) return@withContext false
        if (isReferenced(record)) return@withContext false
        if (record.phase == BitwardenAttachmentRecoveryStore.Phase.ALLOCATED) {
            record = recoveryStore.advance(binding, operationId, record.phase,
                BitwardenAttachmentRecoveryStore.Phase.DELETING, requireCurrent = ctx.requireCurrent)
        }
        ctx.requireCurrent()
        if (isReferenced(record)) return@withContext false
        val response = ctx.vaultApi.deleteAttachment(bearer(ctx.accessToken), ctx.cipherId, remoteId)
        ctx.requireCurrent()
        // A proxy's 404 is not proof that the exact object was removed; read back on a later pass.
        if (response.code() !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT)) {
            throw AttachmentError.NetworkError(response.code())
        }
        recoveryStore.advance(binding, operationId, record.phase,
            BitwardenAttachmentRecoveryStore.Phase.REMOVED, requireCurrent = ctx.requireCurrent)
        true
    }

    private suspend fun recoveryCipher(ctx: UploadContext): takagi.ru.monica.bitwarden.api.CipherApiResponse {
        ctx.requireCurrent()
        val response = ctx.vaultApi.getCipher(bearer(ctx.accessToken), ctx.cipherId)
        ctx.requireCurrent()
        if (!response.isSuccessful) throw AttachmentError.NetworkError(response.code())
        val cipher = response.body() ?: throw AttachmentError.InvalidRemoteData
        takagi.ru.monica.bitwarden.service.BitwardenCipherOwnership.requireWritable(cipher, ctx.cipherId)
        return cipher
    }

    private suspend fun verifyRemoteRecoveryBytes(
        record: BitwardenAttachmentRecoveryStore.Record, remoteId: String, ctx: UploadContext,
    ) {
        val response = ctx.vaultApi.getAttachmentDownload(bearer(ctx.accessToken), ctx.cipherId, remoteId)
        ctx.requireCurrent()
        if (!response.isSuccessful) throw AttachmentError.NetworkError(response.code())
        val info = response.body() ?: throw AttachmentError.InvalidRemoteData
        if (info.id != remoteId || info.key != record.requestKey || info.fileName != record.requestFileName ||
            info.size != record.encryptedSize.toString() || info.url.isBlank()) throw AttachmentError.InvalidRemoteData
        val request = Request.Builder().url(info.url).get().build()
        val client = ctx.clientForUrl(info.url)
        ctx.requireCurrent()
        client.newCall(request).execute().use { download ->
            ctx.requireCurrent()
            if (!download.isSuccessful) throw AttachmentError.NetworkError(download.code)
            val body = download.body ?: throw AttachmentError.InvalidRemoteData
            if (body.contentLength() > record.encryptedSize) throw AttachmentError.InvalidRemoteData
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ctx.requireCurrent()
                    val count = input.read(buffer)
                    if (count == -1) break
                    size += count
                    if (size > record.encryptedSize) throw AttachmentError.InvalidRemoteData
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (size != record.encryptedSize || actual != record.encryptedSha256) throw AttachmentError.InvalidRemoteData
        }
    }

    private suspend fun materializeRecovery(
        record: BitwardenAttachmentRecoveryStore.Record, payload: File, ctx: UploadContext,
    ): Attachment {
        ctx.requireCurrent()
        val wrapping = ctx.wrappingKeyProvider?.invoke()
            ?: SymmetricCryptoKey(ctx.wrappingKey.encKey.copyOf(), ctx.wrappingKey.macKey.copyOf())
        val attachmentKey = try { BitwardenAttachmentCrypto.unwrapAttachmentKey(record.requestKey, wrapping) }
            finally { wrapping.clear() }
        var stagedPath: String? = null
        try {
            val blob = try {
                BitwardenAttachmentCrypto.openDecrypted(payload, attachmentKey).use { storage.writeEncrypted(it) }
            } finally { attachmentKey.clear() }
            stagedPath = blob.relativePath
            val wrapped = try {
                ctx.requireCurrent()
                check(blob.sizeBytes == record.plainSize && blob.sha256Hex == record.plainSha256)
                keyVault.wrap(blob.cek)
            } finally { blob.cek.fill(0) }
            val owner = AttachmentOwner(AttachmentOwner.Kind.valueOf(record.binding.ownerKind), record.binding.ownerId)
            val now = System.currentTimeMillis()
            ctx.requireCurrent()
            return Attachment(parentPasswordId = owner.passwordId, parentSecureItemId = owner.secureItemId,
                source = AttachmentSource.BITWARDEN.name, fileName = record.fileName, mimeType = record.mimeType,
                sizeBytes = blob.sizeBytes, sha256Hex = blob.sha256Hex, wrappedCek = wrapped,
                localPath = blob.relativePath, bitwardenAttachmentId = requireNotNull(record.remoteId),
                bitwardenFileKeyEnc = record.requestKey, downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = now, updatedAt = now)
        } catch (error: Throwable) {
            cleanupStagedBlob(stagedPath)
            throw error
        }
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun cleanupStagedBlob(path: String?) {
        if (path == null) return
        withContext(NonCancellable) { runCatching { storage.delete(path) } }
    }

    private suspend fun uploadAzure(
        ctx: UploadContext,
        url: String,
        ciphertextFile: File
    ) = withContext(Dispatchers.IO) {
        ctx.requireCurrent()
        val transferClient = ctx.clientForUrl(url)
        ctx.requireCurrent()
        val body = ciphertextFile.asRequestBody(OCTET.toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("x-ms-blob-type", "BlockBlob")
            .build()
        val resp = transferClient.newCall(request).execute()
        resp.use {
            ctx.requireCurrent()
            if (!it.isSuccessful) throw AttachmentError.NetworkError(it.code)
        }
    }

    private suspend fun uploadDirect(
        ctx: UploadContext,
        cipherId: String,
        attachmentId: String,
        fileKeyEnc: String,
        ciphertextFile: File
    ) = withContext(Dispatchers.IO) {
        ctx.requireCurrent()
        val keyPart = MultipartBody.Part.createFormData("key", fileKeyEnc)
        val dataBody = ciphertextFile.asRequestBody(OCTET.toMediaTypeOrNull())
        val dataPart = MultipartBody.Part.createFormData(
            "data",
            "blob",
            dataBody
        )
        val resp = ctx.vaultApi.uploadAttachmentDirect(
            authorization = bearer(ctx.accessToken),
            cipherId = cipherId,
            attachmentId = attachmentId,
            key = keyPart,
            data = dataPart
        )
        ctx.requireCurrent()
        if (!resp.isSuccessful) throw AttachmentError.NetworkError(resp.code())
    }

    private fun bearer(token: String): String = "Bearer $token"

    companion object {
        private const val FILE_UPLOAD_TYPE_DIRECT = 0
        private const val FILE_UPLOAD_TYPE_AZURE = 1
        private const val OCTET = "application/octet-stream"

        /** 便于调用方构造上传返回值时生成稳定的临时 attachmentId（若服务端没给）。 */
        fun placeholderAttachmentId(): String = UUID.randomUUID().toString()
    }
}

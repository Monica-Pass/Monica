package takagi.ru.monica.attachments.facade

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import takagi.ru.monica.attachments.executor.BitwardenAttachmentExecutor
import takagi.ru.monica.attachments.executor.BitwardenAttachmentReconciler
import takagi.ru.monica.attachments.executor.KeePassAttachmentExecutor
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.repository.AttachmentRepository
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentPreviewCache
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.attachments.util.AttachmentLogger
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.mdbxPasswordObjectId
import takagi.ru.monica.repository.mdbxSecureItemObjectId
import java.io.File
import java.io.OutputStream

/**
 * 附件子系统对 ViewModel 的唯一入口。
 *
 * Facade 不自己持有账户状态（Plus、Bitwarden premium、kdbx 解锁态等），这些由调用方在
 * [UploadRequest] 或 [BitwardenContext] 中显式传入，避免把附件逻辑与业务模块互锁。
 *
 * 职责分工：
 * - Quota / Size 校验：[AttachmentQuotaPolicy]、[AttachmentSizeValidator]
 * - 字节加解密：executor 层（Local / Bitwarden / KeePass）
 * - Room 事务：通过 [AttachmentRepository]
 *
 * 对应 requirements.md Requirement 4 / 5 / 6 / 7 / 10。
 */
class AttachmentFacade(
    private val context: Context,
    private val repository: AttachmentRepository,
    private val localExecutor: LocalAttachmentExecutor,
    private val bitwardenExecutor: BitwardenAttachmentExecutor,
    private val keepassExecutor: KeePassAttachmentExecutor,
    private val storage: AttachmentStorage,
    private val keyVault: AttachmentKeyVault,
    private val previewCache: AttachmentPreviewCache,
    private val passwordEntryDao: PasswordEntryDao? = null,
    private val secureItemDao: SecureItemDao? = null,
    private val mdbxVaultStore: MdbxRepository? = null,
    /** 用于 `openForPreview` 发出的 FileProvider authority，需与 manifest 中注册的 authority 一致。 */
    private val fileProviderAuthority: String
) {

    suspend fun reconcileBitwardenAttachments(
        owner: AttachmentOwner,
        remoteAttachments: List<CipherAttachmentApiData>?
    ): BitwardenAttachmentReconciler.Report = withContext(Dispatchers.IO) {
        BitwardenAttachmentReconciler(
            repository = repository,
            storage = storage
        ).reconcile(owner, remoteAttachments)
    }

    /**
     * 业务侧发起上传时的最小上下文。
     *
     * [source] 决定走哪个 executor：
     * - LOCAL：自足；[bitwardenContext]/[keepassContext] 应为 null。
     * - BITWARDEN：必须提供 [bitwardenContext]；另外需要 [bitwardenPremium] = true（免费账户会被直接拒绝）。
     * - KEEPASS：必须提供 [keepassContext]。
     */
    data class UploadRequest(
        val owner: AttachmentOwner,
        val source: AttachmentSource,
        val uri: Uri,
        val isPlusActivated: Boolean,
        val bitwardenPremium: Boolean = true,
        val kdbxSoftLimitAccepted: Boolean = false,
        val bitwardenContext: BitwardenContext? = null,
        val keepassContext: KeePassContext? = null
    )

    data class InlineUploadRequest(
        val owner: AttachmentOwner,
        val source: AttachmentSource,
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray,
        val isPlusActivated: Boolean,
        val bitwardenPremium: Boolean = true,
        val kdbxSoftLimitAccepted: Boolean = false,
        val bitwardenContext: BitwardenContext? = null,
        val keepassContext: KeePassContext? = null
    )

    data class BitwardenContext(
        val vaultApi: BitwardenVaultApi,
        val httpClient: OkHttpClient,
        val accessToken: String,
        val cipherId: String,
        /** 用于包裹/解包附件密钥的 cipher 或 user key。 */
        val wrappingKey: SymmetricCryptoKey,
        val isOnline: Boolean
    )

    data class KeePassContext(
        val databaseId: Long,
        val entryUuid: String
    )

    // ---------------------------------------------------------------- 查询

    fun observeByPassword(passwordId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.password(passwordId))

    fun observeBySecureItem(secureItemId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.secureItem(secureItemId))

    fun observe(owner: AttachmentOwner): Flow<List<Attachment>> = repository.observe(owner)

    suspend fun listByPassword(passwordId: Long): List<Attachment> =
        list(AttachmentOwner.password(passwordId))

    suspend fun listBySecureItem(secureItemId: Long): List<Attachment> =
        list(AttachmentOwner.secureItem(secureItemId))

    suspend fun list(owner: AttachmentOwner): List<Attachment> = repository.list(owner)

    suspend fun getById(id: Long): Attachment? = repository.getById(id)

    /**
     * 在密码传输开始前确认全部附件均具有可读取的本地加密缓存。
     *
     * 远端附件会使用对应上下文下载；任一附件缺少字节或上下文时抛出异常，调用方应当保留来源条目。
     */
    suspend fun ensureAttachmentsReadyForTransfer(
        passwordId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): Int = ensureAttachmentsReadyForTransfer(
        owner = AttachmentOwner.password(passwordId),
        bitwardenContext = bitwardenContext,
        keepassContext = keepassContext
    )

    suspend fun ensureAttachmentsReadyForTransfer(
        owner: AttachmentOwner,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): Int = withContext(Dispatchers.IO) {
        val attachments = repository.list(owner)
        attachments.forEach { attachment ->
            ensureLocalCacheForTransfer(
                attachment = attachment,
                sourceBitwardenContext = bitwardenContext,
                sourceKeepassContext = keepassContext
            )
        }
        attachments.size
    }

    // ---------------------------------------------------------------- 添加

    /**
     * 添加附件的统一入口。
     *
     * 所有错误都会以 [AttachmentError] 形式抛出，调用方可以直接翻译成 UI 文案。
     */
    suspend fun addAttachment(request: UploadRequest): Attachment = withContext(Dispatchers.IO) {
        try {
            // 1. Quota（仅本地/Bitwarden/KeePass 都一致地受 Plus 限制）
            val existingCount = repository.countActive(request.owner)
            AttachmentQuotaPolicy.check(existingCount, request.isPlusActivated)?.let { throw it }

            // 2. Size / 类型上限
            val meta = AttachmentUriMetadata.resolve(context, request.uri)
            if (meta.sizeBytes >= 0) {
                val validation = AttachmentSizeValidator.validate(
                    sizeBytes = meta.sizeBytes,
                    source = request.source,
                    userAcceptedSoftLimit = request.kdbxSoftLimitAccepted
                )
                when (validation) {
                    is AttachmentSizeValidator.Result.Ok -> Unit
                    is AttachmentSizeValidator.Result.TooLarge ->
                        throw AttachmentError.TooLarge(validation.limitBytes, validation.actualBytes)
                    is AttachmentSizeValidator.Result.NeedsConfirm -> {
                        // KeePass 软上限：调用方未 opt-in 时阻断，UI 负责重新调用并置 kdbxSoftLimitAccepted=true
                        throw AttachmentError.TooLarge(validation.softLimitBytes, validation.actualBytes)
                    }
                }
            }

            // 3. 按 source 分派
            val attachment = when (request.source) {
                AttachmentSource.LOCAL -> localExecutor.writeFromUri(
                    owner = request.owner,
                    sourceUri = request.uri,
                    fallbackFileName = meta.fileName
                )
                AttachmentSource.BITWARDEN -> {
                    if (!request.bitwardenPremium) throw AttachmentError.PremiumRequired
                    val bw = request.bitwardenContext ?: throw AttachmentError.IoError
                    if (!bw.isOnline) throw AttachmentError.Offline
                    val resolver = context.applicationContext.contentResolver
                    val input = resolver.openInputStream(request.uri) ?: throw AttachmentError.IoError
                    input.use { stream ->
                        bitwardenExecutor.upload(
                            owner = request.owner,
                            fileName = meta.fileName,
                            mimeType = meta.mimeType,
                            source = stream,
                            sizeBytes = meta.sizeBytes.takeIf { it >= 0 } ?: 0L,
                            ctx = BitwardenAttachmentExecutor.UploadContext(
                                vaultApi = bw.vaultApi,
                                httpClient = bw.httpClient,
                                accessToken = bw.accessToken,
                                cipherId = bw.cipherId,
                                wrappingKey = bw.wrappingKey
                            )
                        )
                    }
                }
                AttachmentSource.KEEPASS -> {
                    val kp = request.keepassContext ?: throw AttachmentError.IoError
                    val bytes = readAllBytes(request.uri)
                    keepassExecutor.upload(
                        owner = request.owner,
                        databaseId = kp.databaseId,
                        entryUuid = kp.entryUuid,
                        fileName = meta.fileName,
                        mimeType = meta.mimeType,
                        sourceBytes = bytes
                    )
                }
            }

            // 4. 持久化（executor 返回的 Attachment 已经是完整记录，Room 只负责 insert）
            var id: Long? = null
            try {
                val insertedId = repository.insert(attachment)
                id = insertedId
                attachment.copy(id = insertedId).also { saved ->
                    AttachmentLogger.logOk(
                        event = AttachmentLogger.Event.UPLOAD,
                        attachmentId = id,
                        source = saved.sourceEnum,
                        extras = mapOf(
                            "ownerKind" to request.owner.kind.name,
                            "ownerId" to request.owner.id,
                            "keepassDatabaseId" to request.keepassContext?.databaseId,
                            "keepassEntryUuidPresent" to !request.keepassContext?.entryUuid.isNullOrBlank()
                        )
                    )
                    mirrorAttachmentToMdbx(saved)
                }
            } catch (error: Throwable) {
                id?.let { repository.deleteById(it) }
                if (attachment.sourceEnum == AttachmentSource.LOCAL) {
                    attachment.localPath?.let { storage.delete(it) }
                }
                throw error
            }
        } catch (e: Throwable) {
            AttachmentLogger.logFailure(
                event = AttachmentLogger.Event.UPLOAD,
                attachmentId = null,
                source = request.source,
                error = e,
                extras = mapOf(
                    "ownerKind" to request.owner.kind.name,
                    "ownerId" to request.owner.id,
                    "keepassDatabaseId" to request.keepassContext?.databaseId,
                    "keepassEntryUuidPresent" to !request.keepassContext?.entryUuid.isNullOrBlank()
                )
            )
            throw e
        }
    }

    /** Adds a small in-memory payload without materializing plaintext in a temporary file. */
    suspend fun addInlineAttachment(request: InlineUploadRequest): Attachment =
        withContext(Dispatchers.IO) {
            val existingCount = repository.countActive(request.owner)
            AttachmentQuotaPolicy.check(existingCount, request.isPlusActivated)?.let { throw it }
            when (
                val validation = AttachmentSizeValidator.validate(
                    sizeBytes = request.bytes.size.toLong(),
                    source = request.source,
                    userAcceptedSoftLimit = request.kdbxSoftLimitAccepted
                )
            ) {
                is AttachmentSizeValidator.Result.Ok -> Unit
                is AttachmentSizeValidator.Result.TooLarge ->
                    throw AttachmentError.TooLarge(validation.limitBytes, validation.actualBytes)
                is AttachmentSizeValidator.Result.NeedsConfirm ->
                    throw AttachmentError.TooLarge(validation.softLimitBytes, validation.actualBytes)
            }

            val attachment = when (request.source) {
                AttachmentSource.LOCAL -> localExecutor.writeFromBytes(
                    owner = request.owner,
                    fileName = request.fileName,
                    mimeType = request.mimeType,
                    bytes = request.bytes
                )
                AttachmentSource.BITWARDEN -> {
                    if (!request.bitwardenPremium) throw AttachmentError.PremiumRequired
                    val bw = request.bitwardenContext ?: throw AttachmentError.IoError
                    if (!bw.isOnline) throw AttachmentError.Offline
                    request.bytes.inputStream().use { stream ->
                        bitwardenExecutor.upload(
                            owner = request.owner,
                            fileName = request.fileName,
                            mimeType = request.mimeType,
                            source = stream,
                            sizeBytes = request.bytes.size.toLong(),
                            ctx = BitwardenAttachmentExecutor.UploadContext(
                                vaultApi = bw.vaultApi,
                                httpClient = bw.httpClient,
                                accessToken = bw.accessToken,
                                cipherId = bw.cipherId,
                                wrappingKey = bw.wrappingKey
                            )
                        )
                    }
                }
                AttachmentSource.KEEPASS -> {
                    val kp = request.keepassContext ?: throw AttachmentError.IoError
                    keepassExecutor.upload(
                        owner = request.owner,
                        databaseId = kp.databaseId,
                        entryUuid = kp.entryUuid,
                        fileName = request.fileName,
                        mimeType = request.mimeType,
                        sourceBytes = request.bytes
                    )
                }
            }

            var insertedId: Long? = null
            try {
                val savedId = repository.insert(attachment)
                insertedId = savedId
                attachment.copy(id = savedId).also { saved ->
                    AttachmentLogger.logOk(
                        event = AttachmentLogger.Event.UPLOAD,
                        attachmentId = savedId,
                        source = saved.sourceEnum,
                        extras = mapOf(
                            "ownerKind" to request.owner.kind.name,
                            "ownerId" to request.owner.id,
                            "inline" to true
                        )
                    )
                    mirrorAttachmentToMdbx(saved)
                }
            } catch (error: Throwable) {
                insertedId?.let { repository.deleteById(it) }
                when (attachment.sourceEnum) {
                    AttachmentSource.LOCAL -> Unit
                    AttachmentSource.BITWARDEN -> {
                        val bw = request.bitwardenContext
                        val remoteId = attachment.bitwardenAttachmentId
                        if (bw != null && !remoteId.isNullOrBlank()) {
                            runCatching {
                                bitwardenExecutor.remove(
                                    vaultApi = bw.vaultApi,
                                    accessToken = bw.accessToken,
                                    cipherId = bw.cipherId,
                                    bitwardenAttachmentId = remoteId
                                )
                            }
                        }
                    }
                    AttachmentSource.KEEPASS -> {
                        val kp = request.keepassContext
                        val ref = attachment.keepassBinaryRef
                        if (kp != null && !ref.isNullOrBlank()) {
                            runCatching {
                                keepassExecutor.remove(kp.databaseId, kp.entryUuid, ref)
                            }
                        }
                    }
                }
                attachment.localPath?.let { path -> runCatching { storage.delete(path) } }
                throw error
            }
        }

    /**
     * Promotes selected encrypted local attachments to an existing Bitwarden cipher while keeping
     * their stable owner and file names. Remote uploads finish before any Room row is replaced, so
     * a partial network failure leaves every original local attachment usable.
     */
    suspend fun promoteLocalAttachmentsToBitwarden(
        owner: AttachmentOwner,
        targetContext: BitwardenContext,
        fileNames: Set<String>
    ): Int = withContext(Dispatchers.IO) {
        if (fileNames.isEmpty()) return@withContext 0
        if (!targetContext.isOnline) throw AttachmentError.Offline
        val sources = repository
            .listByOwnerAndSource(owner, AttachmentSource.LOCAL)
            .filter { it.fileName in fileNames }
        if (sources.isEmpty()) return@withContext 0

        val uploaded = mutableListOf<Pair<Attachment, Attachment>>()
        try {
            sources.forEach { source ->
                val remote = localExecutor.openDecrypted(source).use { plainStream ->
                    bitwardenExecutor.upload(
                        owner = owner,
                        fileName = source.fileName,
                        mimeType = source.mimeType,
                        source = plainStream,
                        sizeBytes = source.sizeBytes,
                        ctx = BitwardenAttachmentExecutor.UploadContext(
                            vaultApi = targetContext.vaultApi,
                            httpClient = targetContext.httpClient,
                            accessToken = targetContext.accessToken,
                            cipherId = targetContext.cipherId,
                            wrappingKey = targetContext.wrappingKey
                        )
                    )
                }
                uploaded += source to remote
            }
        } catch (error: Throwable) {
            rollbackBitwardenAttachmentUploads(uploaded.map { it.second }, targetContext)
            throw error
        }

        val replaced = mutableListOf<Pair<Attachment, Attachment>>()
        try {
            uploaded.forEach { (source, remote) ->
                val replacement = remote.copy(
                    id = source.id,
                    parentPasswordId = owner.passwordId,
                    parentSecureItemId = owner.secureItemId,
                    createdAt = source.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    isDeleted = false,
                    deletedAt = null
                )
                check(repository.update(replacement) == 1) {
                    "Bitwarden attachment metadata update failed"
                }
                replaced += source to replacement
            }
        } catch (error: Throwable) {
            replaced.asReversed().forEach { (source, _) -> runCatching { repository.update(source) } }
            rollbackBitwardenAttachmentUploads(uploaded.map { it.second }, targetContext)
            throw error
        }

        replaced.forEach { (source, replacement) ->
            source.localPath
                ?.takeIf { it != replacement.localPath }
                ?.let { oldPath -> storage.delete(oldPath) }
        }
        replaced.size
    }

    // ---------------------------------------------------------------- 读/导出

    /**
     * 确保附件的本地密文缓存存在（若已下载直接返回原记录）。
     *
     * Bitwarden / KeePass 附件在 PENDING 状态下会走对应 executor 的 download。
     */
    suspend fun ensureDownloaded(
        attachmentId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): Attachment = withContext(Dispatchers.IO) {
        val existing = repository.getById(attachmentId) ?: throw AttachmentError.IoError
        if (existing.downloadStateEnum == AttachmentDownloadState.DOWNLOADED &&
            existing.localPath != null && storage.exists(existing.localPath)
        ) {
            return@withContext existing
        }
        repository.markDownloadState(attachmentId, AttachmentDownloadState.DOWNLOADING)
        val refreshed: Attachment = try {
            when (existing.sourceEnum) {
                AttachmentSource.LOCAL -> existing.copy(
                    downloadState = AttachmentDownloadState.DOWNLOADED.name
                )
                AttachmentSource.BITWARDEN -> {
                    val bw = bitwardenContext ?: throw AttachmentError.IoError
                    if (!bw.isOnline) throw AttachmentError.Offline
                    val remote = CipherAttachmentApiData(
                        id = existing.bitwardenAttachmentId ?: throw AttachmentError.CryptoError,
                        fileName = existing.fileName,
                        size = existing.sizeBytes.toString(),
                        sizeName = null,
                        key = existing.bitwardenFileKeyEnc,
                        url = existing.bitwardenUrl
                    )
                    bitwardenExecutor.download(
                        existing = existing,
                        remote = remote,
                        vaultApi = bw.vaultApi,
                        httpClient = bw.httpClient,
                        accessToken = bw.accessToken,
                        cipherId = bw.cipherId,
                        wrappingKey = bw.wrappingKey
                    )
                }
                AttachmentSource.KEEPASS -> {
                    val kp = keepassContext ?: throw AttachmentError.IoError
                    keepassExecutor.download(
                        existing = existing,
                        databaseId = kp.databaseId,
                        entryUuid = kp.entryUuid
                    )
                }
            }
        } catch (e: Throwable) {
            repository.markDownloadState(attachmentId, AttachmentDownloadState.FAILED)
            throw e
        }
        repository.update(refreshed)
        refreshed.copy(id = attachmentId)
    }

    suspend fun readAttachmentBytes(
        attachmentId: Long,
        maxBytes: Int,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        val ready = ensureDownloaded(
            attachmentId = attachmentId,
            bitwardenContext = bitwardenContext,
            keepassContext = keepassContext
        )
        if (ready.sizeBytes > maxBytes.toLong()) throw AttachmentError.TooLarge(
            limitBytes = maxBytes.toLong(),
            actualBytes = ready.sizeBytes
        )
        localExecutor.openDecrypted(ready).use { input ->
            val output = java.io.ByteArrayOutputStream(
                ready.sizeBytes.coerceIn(0L, maxBytes.toLong()).toInt()
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw AttachmentError.TooLarge(
                    limitBytes = maxBytes.toLong(),
                    actualBytes = total.toLong()
                )
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    /**
     * 把已下载的附件解密为临时明文文件，返回可通过 FileProvider 分享的 content URI。
     *
     * 调用方负责后续 `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`。
     */
    suspend fun openForPreview(
        attachmentId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): Uri = withContext(Dispatchers.IO) {
        val ready = ensureDownloaded(attachmentId, bitwardenContext, keepassContext)
        val file = materializePreview(ready)
        try {
            FileProvider.getUriForFile(context.applicationContext, fileProviderAuthority, file)
        } catch (e: Throwable) {
            AttachmentLogger.logFailure(
                event = AttachmentLogger.Event.PREVIEW,
                attachmentId = attachmentId,
                source = null,
                error = e,
                extras = mapOf("fallback" to "file_uri")
            )
            Uri.fromFile(file)
        }
    }

    /** 把附件明文写到用户选择的目标 URI（例如 SAF 创建的文件）。 */
    suspend fun exportToSystem(
        attachmentId: Long,
        targetUri: Uri,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ) = withContext(Dispatchers.IO) {
        val ready = ensureDownloaded(attachmentId, bitwardenContext, keepassContext)
        val wrapped = ready.wrappedCek ?: throw AttachmentError.CryptoError
        val path = ready.localPath ?: throw AttachmentError.IoError
        val cek = try {
            keyVault.unwrap(wrapped)
        } catch (e: Throwable) {
            throw AttachmentError.CryptoError
        }
        val resolver = context.applicationContext.contentResolver
        val out: OutputStream = resolver.openOutputStream(targetUri)
            ?: throw AttachmentError.IoError
        try {
            val plainStream = storage.openDecryptedStream(path, cek)
            plainStream.use { input ->
                out.use { output -> input.copyTo(output) }
            }
        } finally {
            cek.fill(0)
        }
    }

    // ---------------------------------------------------------------- 删除 / 重试

    /** Drop a superseded local record without deleting the binary in its former backend. */
    suspend fun forgetLocalAttachment(attachmentId: Long) = withContext(Dispatchers.IO) {
        val existing = repository.getById(attachmentId) ?: return@withContext
        mirrorAttachmentDeleteToMdbx(existing)
        repository.deleteById(attachmentId)
        existing.localPath?.let { storage.delete(it) }
    }

    suspend fun deleteAttachment(
        attachmentId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ) = withContext(Dispatchers.IO) {
        val existing = repository.getById(attachmentId) ?: return@withContext
        if (existing.sourceEnum == AttachmentSource.LOCAL && isStrictMdbxAttachment(existing)) {
            mirrorAttachmentDeleteToMdbx(existing)
            try {
                check(repository.deleteById(attachmentId) == 1) {
                    "MDBX2 attachment metadata was not deleted"
                }
            } catch (error: Throwable) {
                mirrorAttachmentToMdbx(existing)
                throw error
            }
            existing.localPath?.let { path ->
                if (!storage.delete(path)) {
                    repository.insert(existing)
                    mirrorAttachmentToMdbx(existing)
                    error("MDBX2 attachment metadata was restored because its local blob could not be deleted")
                }
            }
            return@withContext
        }
        mirrorAttachmentDeleteToMdbx(existing)
        when (existing.sourceEnum) {
            AttachmentSource.LOCAL -> {
                localExecutor.delete(existing)
            }
            AttachmentSource.BITWARDEN -> {
                val bw = bitwardenContext ?: throw AttachmentError.IoError
                if (!bw.isOnline) throw AttachmentError.Offline
                val attachmentRemoteId = existing.bitwardenAttachmentId
                if (!attachmentRemoteId.isNullOrBlank()) {
                    bitwardenExecutor.remove(
                        vaultApi = bw.vaultApi,
                        accessToken = bw.accessToken,
                        cipherId = bw.cipherId,
                        bitwardenAttachmentId = attachmentRemoteId
                    )
                }
                existing.localPath?.let { storage.delete(it) }
            }
            AttachmentSource.KEEPASS -> {
                val kp = keepassContext ?: throw AttachmentError.IoError
                val ref = existing.keepassBinaryRef
                if (!ref.isNullOrBlank()) {
                    keepassExecutor.remove(
                        databaseId = kp.databaseId,
                        entryUuid = kp.entryUuid,
                        binaryHashHex = ref
                    )
                }
                existing.localPath?.let { storage.delete(it) }
            }
        }
        repository.deleteById(attachmentId)
    }

    suspend fun retryFailed(
        attachmentId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ): Attachment {
        val existing = repository.getById(attachmentId) ?: throw AttachmentError.IoError
        if (existing.downloadStateEnum == AttachmentDownloadState.DOWNLOADED &&
            existing.localPath != null && storage.exists(existing.localPath)
        ) {
            return existing
        }
        // 把状态先从 FAILED 归位到 PENDING，然后走 ensureDownloaded
        repository.markDownloadState(attachmentId, AttachmentDownloadState.PENDING)
        return ensureDownloaded(attachmentId, bitwardenContext, keepassContext)
    }

    // ---------------------------------------------------------------- 级联

    /**
     * 随密码永久删除一起清理：移除 Room 记录 + 本地密文缓存 + 远端字节。
     *
     * 对于 Bitwarden / KeePass 的远端清理，如果调用方没有传 context（例如密码删除时不持有
     * Bitwarden 会话），这里会只清 Room 与本地缓存，远端的孤儿附件靠后续 `reconcile` 自然对齐。
     */
    suspend fun purgeByPassword(
        passwordId: Long,
        bitwardenContext: BitwardenContext? = null,
        keepassContext: KeePassContext? = null
    ) = withContext(Dispatchers.IO) {
        val items = repository.listByPassword(passwordId, includeDeleted = true)
        for (item in items) {
            when (item.sourceEnum) {
                AttachmentSource.LOCAL -> localExecutor.delete(item)
                AttachmentSource.BITWARDEN -> {
                    val bw = bitwardenContext
                    val remoteId = item.bitwardenAttachmentId
                    if (bw != null && bw.isOnline && !remoteId.isNullOrBlank()) {
                        runCatching {
                            bitwardenExecutor.remove(
                                vaultApi = bw.vaultApi,
                                accessToken = bw.accessToken,
                                cipherId = bw.cipherId,
                                bitwardenAttachmentId = remoteId
                            )
                        }
                    }
                    item.localPath?.let { storage.delete(it) }
                }
                AttachmentSource.KEEPASS -> {
                    val kp = keepassContext
                    val ref = item.keepassBinaryRef
                    if (kp != null && !ref.isNullOrBlank()) {
                        runCatching {
                            keepassExecutor.remove(
                                databaseId = kp.databaseId,
                                entryUuid = kp.entryUuid,
                                binaryHashHex = ref
                            )
                        }
                    }
                    item.localPath?.let { storage.delete(it) }
                }
            }
        }
        repository.purgeByPassword(passwordId)
    }

    suspend fun softDeleteByPassword(passwordId: Long) {
        repository.softDeleteByPassword(passwordId)
    }

    suspend fun restoreByPassword(passwordId: Long) {
        repository.restoreByPassword(passwordId)
    }

    /**
     * 把 [sourcePasswordId] 名下的附件（含本地缓存的 KEEPASS / BITWARDEN 附件）克隆成 LOCAL 附件
     * 挂到 [targetPasswordId] 上。
     *
     * 场景：KeePass / Bitwarden 条目被 COPY 到 Monica 本地后需要把附件带过去。
     * - 只处理 `is_deleted = 0 && local_path != null && wrapped_cek != null` 的源附件；
     * - 每条附件会在 `secure_attachments/` 目录下新建一份独立密文（不与源共享 blob），
     *   避免源/目标之一删除时误伤对方；
     * - 新记录一律以 `source = LOCAL` 存入，清空 bitwarden/keepass 专属字段；
     * - 任一条失败都会继续处理剩余项，失败通过返回值汇报。
     *
     * @return 成功克隆的附件数量。
     */
    suspend fun cloneAttachmentsToNewParent(
        sourcePasswordId: Long,
        targetPasswordId: Long
    ): Int = cloneAttachmentsToNewOwner(
        sourceOwner = AttachmentOwner.password(sourcePasswordId),
        targetOwner = AttachmentOwner.password(targetPasswordId)
    )

    /** Same clone operation for either a password or a secure item owner. */
    suspend fun cloneAttachmentsToNewOwner(
        sourceOwner: AttachmentOwner,
        targetOwner: AttachmentOwner,
        sourceBitwardenContext: BitwardenContext? = null,
        sourceKeepassContext: KeePassContext? = null,
        excludedFileNames: Set<String> = emptySet()
    ): Int = withContext(Dispatchers.IO) {
        if (sourceOwner.id <= 0 || targetOwner.id <= 0 || sourceOwner == targetOwner) {
            return@withContext 0
        }
        val sources = repository.list(sourceOwner)
            .filterNot { it.fileName in excludedFileNames }
        if (sources.isEmpty()) return@withContext 0

        val created = mutableListOf<Attachment>()
        try {
            sources.forEach { source ->
                val ready = ensureLocalCacheForTransfer(
                    attachment = source,
                    sourceBitwardenContext = sourceBitwardenContext,
                    sourceKeepassContext = sourceKeepassContext
                )
                val plainStream = localExecutor.openDecrypted(ready)
                val blob = plainStream.use { storage.writeEncrypted(it) }
                val wrapped = try {
                    keyVault.wrap(blob.cek)
                } catch (e: Throwable) {
                    runCatching { storage.delete(blob.relativePath) }
                    throw AttachmentError.CryptoError
                } finally {
                    blob.cek.fill(0)
                }
                val now = System.currentTimeMillis()
                val cloned = ready.copy(
                    id = 0,
                    parentPasswordId = targetOwner.passwordId,
                    parentSecureItemId = targetOwner.secureItemId,
                    source = AttachmentSource.LOCAL.name,
                    localPath = blob.relativePath,
                    wrappedCek = wrapped,
                    sizeBytes = blob.sizeBytes.takeIf { it > 0 } ?: ready.sizeBytes,
                    sha256Hex = blob.sha256Hex,
                    bitwardenAttachmentId = null,
                    bitwardenUrl = null,
                    bitwardenFileKeyEnc = null,
                    keepassBinaryRef = null,
                    downloadState = AttachmentDownloadState.DOWNLOADED.name,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null
                )
                val insertedId = try {
                    repository.insert(cloned)
                } catch (error: Throwable) {
                    storage.delete(blob.relativePath)
                    throw error
                }
                val saved = cloned.copy(id = insertedId)
                try {
                    mirrorAttachmentToMdbx(saved, requireSuccess = true)
                } catch (error: Throwable) {
                    repository.deleteById(insertedId)
                    storage.delete(blob.relativePath)
                    throw error
                }
                created += saved
            }
        } catch (error: Throwable) {
            created.asReversed().forEach { cloned ->
                runCatching { mirrorAttachmentDeleteToMdbx(cloned) }
                runCatching { repository.deleteById(cloned.id) }
                cloned.localPath?.let { path -> runCatching { storage.delete(path) } }
            }
            AttachmentLogger.logFailure(
                event = AttachmentLogger.Event.CLONE,
                attachmentId = null,
                source = null,
                error = error,
                extras = mapOf(
                    "source_owner" to sourceOwner.toString(),
                    "target_owner" to targetOwner.toString(),
                    "attachment_count" to sources.size
                )
            )
            throw error
        }
        created.size
    }

    /**
     * 把来源密码的全部附件上传到目标 Bitwarden cipher。
     *
     * 复制场景会插入新的附件记录；移动场景中来源与目标密码 ID 相同时会替换原记录。
     */
    suspend fun copyAttachmentsToBitwardenEntry(
        sourcePasswordId: Long,
        targetPasswordId: Long,
        targetContext: BitwardenContext,
        sourceBitwardenContext: BitwardenContext? = null,
        sourceKeepassContext: KeePassContext? = null
    ): Int = withContext(Dispatchers.IO) {
        if (sourcePasswordId <= 0L || targetPasswordId <= 0L) return@withContext 0
        if (!targetContext.isOnline) throw AttachmentError.Offline
        val sources = repository.listByPassword(sourcePasswordId)
        if (sources.isEmpty()) return@withContext 0

        val readySources = sources.map { source ->
            ensureLocalCacheForTransfer(
                attachment = source,
                sourceBitwardenContext = sourceBitwardenContext,
                sourceKeepassContext = sourceKeepassContext
            )
        }
        val uploaded = mutableListOf<Pair<Attachment, Attachment>>()
        try {
            readySources.forEach { source ->
                val targetAttachment = localExecutor.openDecrypted(source).use { plainStream ->
                    bitwardenExecutor.upload(
                        parentPasswordId = targetPasswordId,
                        fileName = source.fileName,
                        mimeType = source.mimeType,
                        source = plainStream,
                        sizeBytes = source.sizeBytes,
                        ctx = BitwardenAttachmentExecutor.UploadContext(
                            vaultApi = targetContext.vaultApi,
                            httpClient = targetContext.httpClient,
                            accessToken = targetContext.accessToken,
                            cipherId = targetContext.cipherId,
                            wrappingKey = targetContext.wrappingKey
                        )
                    )
                }
                uploaded += source to targetAttachment
            }
        } catch (error: Throwable) {
            rollbackBitwardenAttachmentUploads(uploaded.map { it.second }, targetContext)
            throw error
        }

        val persisted = mutableListOf<Pair<Attachment, Attachment>>()
        try {
            uploaded.forEach { (source, targetAttachment) ->
                val now = System.currentTimeMillis()
                val replacingSource = sourcePasswordId == targetPasswordId
                val metadata = targetAttachment.copy(
                    id = if (replacingSource) source.id else 0L,
                    parentPasswordId = targetPasswordId,
                    createdAt = if (replacingSource) source.createdAt else now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null
                )
                if (replacingSource) {
                    check(repository.update(metadata) == 1) { "Bitwarden attachment metadata update failed" }
                } else {
                    val insertedId = repository.insert(metadata)
                    check(insertedId > 0L) { "Bitwarden attachment metadata insert failed" }
                }
                persisted += source to metadata
            }
        } catch (error: Throwable) {
            persisted.asReversed().forEach { (source, metadata) ->
                if (sourcePasswordId == targetPasswordId) {
                    runCatching { repository.update(source) }
                } else {
                    val remoteId = metadata.bitwardenAttachmentId
                    if (!remoteId.isNullOrBlank()) {
                        repository.getByBitwardenAttachmentId(remoteId)?.let { stored ->
                            runCatching { repository.deleteById(stored.id) }
                        }
                    }
                }
            }
            rollbackBitwardenAttachmentUploads(uploaded.map { it.second }, targetContext)
            throw error
        }

        if (sourcePasswordId == targetPasswordId) {
            persisted.forEach { (source, metadata) ->
                val oldPath = source.localPath
                if (!oldPath.isNullOrBlank() && oldPath != metadata.localPath) {
                    storage.delete(oldPath)
                }
            }
        }
        persisted.size
    }

    /**
     * Before a KeePass-backed password leaves KDBX ownership, make every active
     * KeePass attachment self-contained in Monica's local encrypted store, then
     * rewrite its source to LOCAL. If bytes cannot be materialized, callers must
     * keep the source KDBX entry intact.
     */
    suspend fun materializeKeePassAttachmentsForLocal(
        passwordId: Long,
        databaseId: Long,
        entryUuid: String
    ): Int = withContext(Dispatchers.IO) {
        if (passwordId <= 0 || entryUuid.isBlank()) return@withContext 0
        val sources = repository.listByParentAndSource(passwordId, AttachmentSource.KEEPASS)
        if (sources.isEmpty()) return@withContext 0

        val keepassContext = KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        sources.forEach { source ->
            ensureLocalCacheForTransfer(
                attachment = source,
                sourceBitwardenContext = null,
                sourceKeepassContext = keepassContext
            )
        }

        val unresolved = repository.listByParentAndSource(passwordId, AttachmentSource.KEEPASS)
            .filter { it.localPath.isNullOrBlank() || it.wrappedCek.isNullOrBlank() }
        if (unresolved.isNotEmpty()) {
            throw AttachmentError.IoError
        }

        repository.convertSourceToLocal(passwordId, AttachmentSource.KEEPASS)
    }

    suspend fun materializeBitwardenAttachmentsForLocal(
        passwordId: Long,
        bitwardenContext: BitwardenContext
    ): Int = withContext(Dispatchers.IO) {
        if (passwordId <= 0L) return@withContext 0
        val sources = repository.listByParentAndSource(passwordId, AttachmentSource.BITWARDEN)
        if (sources.isEmpty()) return@withContext 0
        sources.forEach { source ->
            ensureLocalCacheForTransfer(
                attachment = source,
                sourceBitwardenContext = bitwardenContext,
                sourceKeepassContext = null
            )
        }
        repository.convertSourceToLocal(passwordId, AttachmentSource.BITWARDEN)
    }

    /**
     * Copy all active attachments with available bytes into a target KDBX entry.
     *
     * For a move, pass [targetPasswordId] equal to [sourcePasswordId]; existing
     * Room rows are converted to KEEPASS metadata. For a pure KDBX copy with no
     * Room target yet, pass null and the next KeePass projection reconcile will
     * discover the KDBX attachments.
     */
    suspend fun copyAttachmentsToKeePassEntry(
        sourcePasswordId: Long,
        targetPasswordId: Long?,
        targetDatabaseId: Long,
        targetEntryUuid: String,
        sourceKeepassDatabaseId: Long? = null,
        sourceKeepassEntryUuid: String? = null,
        sourceBitwardenContext: BitwardenContext? = null
    ): Int = withContext(Dispatchers.IO) {
        if (sourcePasswordId <= 0 || targetEntryUuid.isBlank()) return@withContext 0
        val sources = repository.listByPassword(sourcePasswordId)
        if (sources.isEmpty()) return@withContext 0

        val sourceKeepassContext = if (
            sourceKeepassDatabaseId != null &&
            !sourceKeepassEntryUuid.isNullOrBlank()
        ) {
            KeePassContext(sourceKeepassDatabaseId, sourceKeepassEntryUuid)
        } else {
            null
        }

        var copied = 0
        sources.forEach { source ->
            val ready = ensureLocalCacheForTransfer(
                attachment = source,
                sourceBitwardenContext = sourceBitwardenContext,
                sourceKeepassContext = sourceKeepassContext
            )
            val bytes = localExecutor.openDecrypted(ready).use { it.readBytes() }
            val uploaded = keepassExecutor.upload(
                parentPasswordId = targetPasswordId ?: sourcePasswordId,
                databaseId = targetDatabaseId,
                entryUuid = targetEntryUuid,
                fileName = ready.fileName,
                mimeType = ready.mimeType,
                sourceBytes = bytes
            )

            if (targetPasswordId != null && targetPasswordId > 0) {
                val now = System.currentTimeMillis()
                val metadata = uploaded.copy(
                    id = if (targetPasswordId == sourcePasswordId) source.id else 0,
                    parentPasswordId = targetPasswordId,
                    createdAt = if (targetPasswordId == sourcePasswordId) source.createdAt else now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null
                )
                if (targetPasswordId == sourcePasswordId) {
                    repository.update(metadata)
                } else {
                    repository.insert(metadata)
                }
            }
            copied++
        }
        copied
    }

    /** 将当前 Room 密码下的全部附件强制写入其 MDBX 数据库。 */
    suspend fun mirrorAttachmentsForPassword(passwordId: Long): Int = withContext(Dispatchers.IO) {
        val attachments = repository.listByPassword(passwordId)
        attachments.forEach { attachment ->
            val ready = ensureLocalCacheForTransfer(
                attachment = attachment,
                sourceBitwardenContext = null,
                sourceKeepassContext = null
            )
            mirrorAttachmentToMdbx(ready, requireSuccess = true)
        }
        attachments.size
    }

    /**
     * 在密码跨 MDBX 数据库移动后迁移附件对象；目标写入全部成功后才删除来源对象。
     */
    suspend fun relocateMdbxAttachments(
        sourceEntry: PasswordEntry,
        targetEntry: PasswordEntry
    ): Int = withContext(Dispatchers.IO) {
        val sourceDatabaseId = sourceEntry.mdbxDatabaseId
        val targetDatabaseId = targetEntry.mdbxDatabaseId
        if (sourceDatabaseId == targetDatabaseId) return@withContext 0
        val vaultStore = mdbxVaultStore ?: return@withContext 0
        val attachments = repository.listByPassword(targetEntry.id)
        if (attachments.isEmpty()) return@withContext 0
        val readyAttachments = attachments.map { attachment ->
            ensureLocalCacheForTransfer(
                attachment = attachment,
                sourceBitwardenContext = null,
                sourceKeepassContext = null
            )
        }
        val sourceParentId = sourceDatabaseId?.let { mdbxPasswordObjectId(sourceEntry) }
        val targetParentId = targetDatabaseId?.let { mdbxPasswordObjectId(targetEntry) }

        try {
            if (targetDatabaseId != null && targetParentId != null) {
                readyAttachments.forEach { attachment ->
                    vaultStore.upsertAttachment(targetDatabaseId, targetParentId, attachment)
                }
            }
            if (sourceDatabaseId != null && sourceParentId != null) {
                readyAttachments.forEach { attachment ->
                    vaultStore.deleteAttachment(sourceDatabaseId, sourceParentId, attachment)
                }
            }
        } catch (error: Throwable) {
            if (sourceDatabaseId != null && sourceParentId != null) {
                readyAttachments.forEach { attachment ->
                    runCatching { vaultStore.upsertAttachment(sourceDatabaseId, sourceParentId, attachment) }
                }
            }
            if (targetDatabaseId != null && targetParentId != null) {
                readyAttachments.forEach { attachment ->
                    runCatching { vaultStore.deleteAttachment(targetDatabaseId, targetParentId, attachment) }
                }
            }
            throw error
        }
        readyAttachments.size
    }

    suspend fun removeAttachmentsFromMdbxSource(sourceEntry: PasswordEntry): Int =
        withContext(Dispatchers.IO) {
            val databaseId = sourceEntry.mdbxDatabaseId ?: return@withContext 0
            val vaultStore = mdbxVaultStore ?: return@withContext 0
            val parentEntryId = mdbxPasswordObjectId(sourceEntry)
            val attachments = repository.listByPassword(sourceEntry.id)
            val deleted = mutableListOf<Attachment>()
            try {
                attachments.forEach { attachment ->
                    vaultStore.deleteAttachment(databaseId, parentEntryId, attachment)
                    deleted += attachment
                }
            } catch (error: Throwable) {
                deleted.forEach { attachment ->
                    runCatching { vaultStore.upsertAttachment(databaseId, parentEntryId, attachment) }
                }
                throw error
            }
            attachments.size
        }

    /**
     * 清理已被 Room 遗弃的本地密文文件（例如通过 `ON DELETE CASCADE` 因密码永久删除
     * 被带走元数据，但磁盘上的 `<uuid>.enc` 没人兜底清理时）。
     *
     * 建议在以下时机调用：
     * - 应用启动一次；
     * - 用户从回收站永久删除密码时；
     * - 备份恢复完成后。
     *
     * @return 实际删除的孤儿文件数量。
     */
    suspend fun purgeOrphanedLocalBlobs(): Int = withContext(Dispatchers.IO) {
        val referenced = repository.allReferencedLocalPaths()
        val onDisk = storage.listAllBlobs()
        var removed = 0
        for (name in onDisk) {
            if (name !in referenced) {
                if (storage.delete(name)) {
                    removed++
                }
            }
        }
        if (removed > 0) {
            AttachmentLogger.logOk(
                event = AttachmentLogger.Event.CLEANUP,
                attachmentId = null,
                source = AttachmentSource.LOCAL,
                extras = mapOf("orphan_blobs_removed" to removed)
            )
        }
        removed
    }

    // ---------------------------------------------------------------- 内部

    private fun readAllBytes(uri: Uri): ByteArray {
        val resolver = context.applicationContext.contentResolver
        val input = resolver.openInputStream(uri) ?: throw AttachmentError.IoError
        return input.use { it.readBytes() }
    }

    private suspend fun ensureLocalCacheForTransfer(
        attachment: Attachment,
        sourceBitwardenContext: BitwardenContext?,
        sourceKeepassContext: KeePassContext?
    ): Attachment {
        if (!attachment.localPath.isNullOrBlank() &&
            !attachment.wrappedCek.isNullOrBlank() &&
            storage.exists(attachment.localPath)
        ) {
            return attachment
        }

        val downloaded = when (attachment.sourceEnum) {
            AttachmentSource.LOCAL -> throw AttachmentError.IoError
            AttachmentSource.BITWARDEN -> ensureDownloaded(
                attachmentId = attachment.id,
                bitwardenContext = sourceBitwardenContext ?: throw AttachmentError.IoError
            )
            AttachmentSource.KEEPASS -> ensureDownloaded(
                attachmentId = attachment.id,
                keepassContext = sourceKeepassContext ?: throw AttachmentError.IoError
            )
        }
        if (downloaded.localPath.isNullOrBlank() ||
            downloaded.wrappedCek.isNullOrBlank() ||
            !storage.exists(downloaded.localPath)
        ) {
            throw AttachmentError.IoError
        }
        return downloaded
    }

    private suspend fun rollbackBitwardenAttachmentUploads(
        uploaded: List<Attachment>,
        targetContext: BitwardenContext
    ) {
        uploaded.asReversed().forEach { attachment ->
            attachment.bitwardenAttachmentId?.takeIf(String::isNotBlank)?.let { remoteId ->
                runCatching {
                    bitwardenExecutor.remove(
                        vaultApi = targetContext.vaultApi,
                        accessToken = targetContext.accessToken,
                        cipherId = targetContext.cipherId,
                        bitwardenAttachmentId = remoteId
                    )
                }
            }
            attachment.localPath?.let { path -> runCatching { storage.delete(path) } }
        }
    }

    private suspend fun mirrorAttachmentToMdbx(
        attachment: Attachment,
        requireSuccess: Boolean = false
    ) {
        val vaultStore = mdbxVaultStore ?: return
        if (attachment.localPath.isNullOrBlank() || attachment.wrappedCek.isNullOrBlank()) return
        val owner = resolveMdbxAttachmentOwner(attachment) ?: return
        try {
            vaultStore.upsertAttachment(owner.databaseId, owner.entryId, attachment)
        } catch (error: Throwable) {
            AttachmentLogger.logFailure(
                event = AttachmentLogger.Event.UPLOAD,
                attachmentId = attachment.id,
                source = attachment.sourceEnum,
                error = error,
                extras = mapOf("mdbx_database_id" to owner.databaseId)
            )
            if (requireSuccess || vaultStore.requiresStrictMutationConsistency(owner.databaseId)) throw error
        }
    }

    private suspend fun mirrorAttachmentDeleteToMdbx(attachment: Attachment) {
        val vaultStore = mdbxVaultStore ?: return
        val owner = resolveMdbxAttachmentOwner(attachment) ?: return
        try {
            vaultStore.deleteAttachment(owner.databaseId, owner.entryId, attachment)
        } catch (error: Throwable) {
            AttachmentLogger.logFailure(
                event = AttachmentLogger.Event.DELETE,
                attachmentId = attachment.id,
                source = attachment.sourceEnum,
                error = error,
                extras = mapOf("mdbx_database_id" to owner.databaseId)
            )
            if (vaultStore.requiresStrictMutationConsistency(owner.databaseId)) throw error
        }
    }

    private suspend fun isStrictMdbxAttachment(attachment: Attachment): Boolean {
        val vaultStore = mdbxVaultStore ?: return false
        val owner = resolveMdbxAttachmentOwner(attachment) ?: return false
        return vaultStore.requiresStrictMutationConsistency(owner.databaseId)
    }

    private suspend fun resolveMdbxAttachmentOwner(attachment: Attachment): MdbxAttachmentOwner? {
        val owner = attachment.owner ?: return null
        return when (owner.kind) {
            AttachmentOwner.Kind.PASSWORD -> {
                val parent = passwordEntryDao?.getPasswordEntryById(owner.id) ?: return null
                val databaseId = parent.mdbxDatabaseId ?: return null
                MdbxAttachmentOwner(databaseId, mdbxPasswordObjectId(parent))
            }
            AttachmentOwner.Kind.SECURE_ITEM -> {
                val parent = secureItemDao?.getItemById(owner.id) ?: return null
                val databaseId = parent.mdbxDatabaseId ?: return null
                MdbxAttachmentOwner(databaseId, mdbxSecureItemObjectId(parent))
            }
        }
    }

    private data class MdbxAttachmentOwner(
        val databaseId: Long,
        val entryId: String
    )

    private suspend fun materializePreview(attachment: Attachment): File {
        val wrapped = attachment.wrappedCek ?: throw AttachmentError.CryptoError
        val path = attachment.localPath ?: throw AttachmentError.IoError
        val cek = try {
            keyVault.unwrap(wrapped)
        } catch (e: Throwable) {
            throw AttachmentError.CryptoError
        }
        return try {
            val plainStream = storage.openDecryptedStream(path, cek)
            plainStream.use { input ->
                previewCache.materialize(attachment.fileName, input)
            }
        } finally {
            cek.fill(0)
        }
    }

    companion object {
        private const val TAG = "AttachmentFacade"
    }
}


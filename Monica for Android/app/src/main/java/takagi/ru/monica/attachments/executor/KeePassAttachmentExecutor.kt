package takagi.ru.monica.attachments.executor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.utils.KeePassErrorCode
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassOperationException
import java.io.InputStream

/**
 * KeePass 附件读写执行器。
 *
 * 对 [KeePassKdbxService] 新增的附件 API 做一层薄壳，把 kdbx `Entry.binaries` 的
 * 原始字节与 Monica 的 [Attachment] / [AttachmentStorage] 密文格式互转：
 *
 * - `upload`：原始字节 → kdbx binary pool，同时在 Monica 本地 Local_Encrypted_Store
 *   再写一份缓存密文（便于后续 offline 预览/导出）。
 * - `download`：对已经在 kdbx pool 里的 binary，解出字节 → 再以 Monica 本地格式缓存。
 * - `remove`：从 kdbx Entry.binaries 移除；池内不再被引用的 BinaryData 由
 *   `KeePassKdbxService.deleteAttachmentFromEntry` 一起释放。
 * - `reconcile`：打开 KeePass 密码时把 kdbx 中的 Entry.binaries 与本地 Room 对齐。
 *
 * 对应 requirements.md Requirement 6；OOM / kotpass 容量异常统一转成
 * [AttachmentError.KdbxCapacityExceeded]；数据库未解锁转成 [AttachmentError.KdbxLocked]。
 */
class KeePassAttachmentExecutor(
    private val context: Context,
    private val kdbxServiceProvider: () -> KeePassKdbxService,
    private val storage: AttachmentStorage,
    private val keyVault: AttachmentKeyVault
) {
    constructor(
        context: Context,
        kdbxService: KeePassKdbxService,
        storage: AttachmentStorage,
        keyVault: AttachmentKeyVault
    ) : this(
        context = context,
        kdbxServiceProvider = { kdbxService },
        storage = storage,
        keyVault = keyVault
    )

    private val kdbxService: KeePassKdbxService
        get() = kdbxServiceProvider()


    /**
     * 把明文字节写入 KeePass 数据库，并在 Monica 本地缓存一份 GCM 密文副本。
     *
     * 失败时：
     * - 数据库未解锁 → [AttachmentError.KdbxLocked]
     * - OOM 或 kotpass 容量异常 → [AttachmentError.KdbxCapacityExceeded]
     * - 读 URI 失败 → [AttachmentError.IoError]
     *
     * @param sourceBytes 已经读进来的明文字节。KeePass 路径下 kotpass 无论如何都要求一次性
     *                    的 ByteArray（kdbx 写入逻辑本身就持有全文件），因此这里直接吃字节。
     */
    suspend fun upload(
        parentPasswordId: Long,
        databaseId: Long,
        entryUuid: String,
        fileName: String,
        mimeType: String,
        sourceBytes: ByteArray
    ): Attachment = upload(
        owner = AttachmentOwner.password(parentPasswordId),
        databaseId = databaseId,
        entryUuid = entryUuid,
        fileName = fileName,
        mimeType = mimeType,
        sourceBytes = sourceBytes
    )

    suspend fun upload(
        owner: AttachmentOwner,
        databaseId: Long,
        entryUuid: String,
        fileName: String,
        mimeType: String,
        sourceBytes: ByteArray
    ): Attachment = withContext(Dispatchers.IO) {
        val kdbxInfo = try {
            if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(owner.id)) {
                val record = requireNotNull(takagi.ru.monica.attachments.AttachmentContainer.originalKeePassStore)
                    .addAttachment(owner, fileName, sourceBytes)
                val asset = record.attachments.last { it.name == fileName.trim() }
                KeePassKdbxService.KeePassAttachmentInfo(asset.hash, asset.name, (asset.binary?.rawContent?.size ?: 0).toLong(), asset.binary?.memoryProtection ?: false)
            } else kdbxService.addAttachmentToEntry(
                databaseId = databaseId,
                entryUuid = entryUuid,
                fileName = fileName,
                bytes = sourceBytes
            ).getOrThrow()
        } catch (e: OutOfMemoryError) {
            throw AttachmentError.KdbxCapacityExceeded
        } catch (e: Throwable) {
            throw mapKdbxFailure(e)
        }

        // 同步写一份本地 GCM 缓存（便于预览/导出）
        val blob = try {
            sourceBytes.inputStream().use { storage.writeEncrypted(it) }
        } catch (e: Throwable) {
            // kdbx 已写成功；本地缓存失败不回滚 kdbx，但把该附件标记为 PENDING 以便下次点击重新从 kdbx 取。
            val now = System.currentTimeMillis()
            return@withContext Attachment(
                id = 0,
                parentPasswordId = owner.passwordId,
                parentSecureItemId = owner.secureItemId,
                source = AttachmentSource.KEEPASS.name,
                fileName = kdbxInfo.fileName,
                mimeType = mimeType,
                sizeBytes = kdbxInfo.sizeBytes,
                sha256Hex = null,
                wrappedCek = null,
                localPath = null,
                keepassBinaryRef = KeePassAttachmentRef.from(kdbxInfo.hashHex, kdbxInfo.fileName).encode(),
                downloadState = AttachmentDownloadState.PENDING.name,
                createdAt = now,
                updatedAt = now
            )
        }

        val wrappedLocalCek = try {
            keyVault.wrap(blob.cek)
        } catch (e: Throwable) {
            runCatching { storage.delete(blob.relativePath) }
            throw AttachmentError.CryptoError
        } finally {
            blob.cek.fill(0)
        }

        val now = System.currentTimeMillis()
        Attachment(
            id = 0,
            parentPasswordId = owner.passwordId,
            parentSecureItemId = owner.secureItemId,
            source = AttachmentSource.KEEPASS.name,
            fileName = kdbxInfo.fileName,
            mimeType = mimeType,
            sizeBytes = kdbxInfo.sizeBytes,
            sha256Hex = blob.sha256Hex,
            wrappedCek = wrappedLocalCek,
            localPath = blob.relativePath,
            keepassBinaryRef = KeePassAttachmentRef.from(kdbxInfo.hashHex, kdbxInfo.fileName).encode(),
            downloadState = AttachmentDownloadState.DOWNLOADED.name,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * 把已经在 kdbx 池里的某个附件字节"物化"到 Monica 本地密文缓存，并返回更新后的 [Attachment]。
     *
     * 适用场景：
     * - 用户第一次点击一个处于 [AttachmentDownloadState.PENDING] 的 KeePass 附件；
     * - 或 `localPath` 被外部清理后需要重建缓存。
     */
    suspend fun download(
        existing: Attachment,
        databaseId: Long,
        entryUuid: String
    ): Attachment = withContext(Dispatchers.IO) {
        val ref = existing.keepassBinaryRef
            ?: throw AttachmentError.CryptoError
        val bytes = try {
            kdbxService.readAttachmentBytes(databaseId, entryUuid, ref).getOrThrow()
        } catch (e: Throwable) {
            throw mapKdbxFailure(e)
        }
        val blob = bytes.inputStream().use { storage.writeEncrypted(it) }
        val wrappedLocalCek = try {
            keyVault.wrap(blob.cek)
        } catch (e: Throwable) {
            runCatching { storage.delete(blob.relativePath) }
            throw AttachmentError.CryptoError
        } finally {
            blob.cek.fill(0)
        }
        existing.copy(
            localPath = blob.relativePath,
            wrappedCek = wrappedLocalCek,
            sha256Hex = blob.sha256Hex,
            sizeBytes = blob.sizeBytes.takeIf { it > 0 } ?: existing.sizeBytes,
            downloadState = AttachmentDownloadState.DOWNLOADED.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 从 kdbx 中移除 Entry.binaries 里指定 hash 的引用；池内若无其他引用则释放。
     * 返回 true 表示确实从 kdbx 中删掉了一条；false 表示本来就没有。
     */
    suspend fun remove(
        databaseId: Long,
        entryUuid: String,
        binaryHashHex: String,
        owner: AttachmentOwner? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (owner != null && takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(owner.id)) {
                requireNotNull(takagi.ru.monica.attachments.AttachmentContainer.originalKeePassStore)
                    .removeAttachment(owner, binaryHashHex)
                true
            } else kdbxService.deleteAttachmentFromEntry(
                databaseId = databaseId,
                entryUuid = entryUuid,
                hashHex = binaryHashHex
            ).getOrThrow()
        } catch (e: Throwable) {
            throw mapKdbxFailure(e)
        }
    }

    /** 把 kdbx Entry.binaries 的快照与给定的本地记录做差异，返回"应该存在的" Attachment 列表骨架。 */
    suspend fun snapshotAttachments(
        databaseId: Long,
        entryUuid: String
    ): List<KeePassKdbxService.KeePassAttachmentInfo> = withContext(Dispatchers.IO) {
        try {
            kdbxService.readEntryAttachments(databaseId, entryUuid).getOrThrow()
        } catch (e: Throwable) {
            throw mapKdbxFailure(e)
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun mapKdbxFailure(e: Throwable): AttachmentError = when (e) {
        is AttachmentError -> e
        is OutOfMemoryError -> AttachmentError.KdbxCapacityExceeded
        is KeePassOperationException -> if (e.code == KeePassErrorCode.INVALID_CREDENTIAL) {
            AttachmentError.KdbxLocked
        } else {
            AttachmentError.IoError
        }
        is IllegalStateException -> if (e.message?.contains("cap", ignoreCase = true) == true) {
            AttachmentError.KdbxCapacityExceeded
        } else if (
            e.message?.contains("credential", ignoreCase = true) == true ||
            e.message?.contains("凭据") == true
        ) {
            AttachmentError.KdbxLocked
        } else {
            AttachmentError.IoError
        }
        else -> AttachmentError.IoError
    }
}

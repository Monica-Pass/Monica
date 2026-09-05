package takagi.ru.monica.attachments.executor

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import java.io.InputStream

/**
 * 本地附件读写执行器：负责把 SAF URI 流式落盘为加密密文，
 * 并在解码时还原 [InputStream]。
 *
 * 不承担配额/大小校验，那些由上游 [takagi.ru.monica.attachments.facade.AttachmentQuotaPolicy]
 * 与 [takagi.ru.monica.attachments.facade.AttachmentSizeValidator] 处理。
 */
class LocalAttachmentExecutor(
    private val context: Context,
    private val storage: AttachmentStorage,
    private val keyVault: AttachmentKeyVault
) {

    /**
     * 把 [sourceUri] 指向的内容加密存入本地，返回可直接 `insert` 的 [Attachment]。
     *
     * 失败时会自动清理半成品密文，调用方只需要捕获异常决定如何向 UI 呈现。
     */
    suspend fun writeFromUri(
        parentPasswordId: Long,
        sourceUri: Uri,
        fallbackFileName: String? = null
    ): Attachment = writeFromUri(
        owner = AttachmentOwner.password(parentPasswordId),
        sourceUri = sourceUri,
        fallbackFileName = fallbackFileName
    )

    suspend fun writeFromUri(
        owner: AttachmentOwner,
        sourceUri: Uri,
        fallbackFileName: String? = null
    ): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val fileName = resolveDisplayName(sourceUri) ?: fallbackFileName ?: DEFAULT_FILE_NAME
        val mimeType = resolver.getType(sourceUri) ?: guessMimeType(fileName)

        val stream: InputStream = resolver.openInputStream(sourceUri)
            ?: throw AttachmentError.IoError
        val blob = stream.use { storage.writeEncrypted(it) }
        val now = System.currentTimeMillis()

        val wrapped = try {
            keyVault.wrap(blob.cek)
        } catch (e: Throwable) {
            runCatching { storage.delete(blob.relativePath) }
            throw AttachmentError.CryptoError
        } finally {
            // CEK 在内存中使用完毕，覆盖一次
            blob.cek.fill(0)
        }

        Attachment(
            id = 0,
            parentPasswordId = owner.passwordId,
            parentSecureItemId = owner.secureItemId,
            source = AttachmentSource.LOCAL.name,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = blob.sizeBytes,
            sha256Hex = blob.sha256Hex,
            wrappedCek = wrapped,
            localPath = blob.relativePath,
            downloadState = AttachmentDownloadState.DOWNLOADED.name,
            createdAt = now,
            updatedAt = now
        )
    }

    /** Writes an already-normalized small payload without creating a plaintext temporary file. */
    suspend fun writeFromBytes(
        owner: AttachmentOwner,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Attachment = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw AttachmentError.IoError
        val safeFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .takeIf(String::isNotEmpty)
            ?: DEFAULT_FILE_NAME
        val blob = bytes.inputStream().use { storage.writeEncrypted(it) }
        val now = System.currentTimeMillis()
        val wrapped = try {
            keyVault.wrap(blob.cek)
        } catch (error: Throwable) {
            runCatching { storage.delete(blob.relativePath) }
            throw AttachmentError.CryptoError
        } finally {
            blob.cek.fill(0)
        }
        Attachment(
            id = 0,
            parentPasswordId = owner.passwordId,
            parentSecureItemId = owner.secureItemId,
            source = AttachmentSource.LOCAL.name,
            fileName = safeFileName,
            mimeType = mimeType.ifBlank { guessMimeType(safeFileName) },
            sizeBytes = blob.sizeBytes,
            sha256Hex = blob.sha256Hex,
            wrappedCek = wrapped,
            localPath = blob.relativePath,
            downloadState = AttachmentDownloadState.DOWNLOADED.name,
            createdAt = now,
            updatedAt = now
        )
    }

    /** 打开一个已 DOWNLOADED 的附件用于读取明文字节。 */
    suspend fun openDecrypted(attachment: Attachment): InputStream = withContext(Dispatchers.IO) {
        val path = attachment.localPath ?: throw AttachmentError.IoError
        val wrapped = attachment.wrappedCek ?: throw AttachmentError.CryptoError
        val cek = try {
            keyVault.unwrap(wrapped)
        } catch (e: Throwable) {
            throw AttachmentError.CryptoError
        }
        try {
            storage.openDecryptedStream(path, cek)
        } finally {
            cek.fill(0)
        }
    }

    /** 删除本地密文。不动 Room 记录。幂等。 */
    suspend fun delete(attachment: Attachment) {
        val path = attachment.localPath ?: return
        storage.delete(path)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = context.applicationContext.contentResolver
        return try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isBlank()) return DEFAULT_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: DEFAULT_MIME
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "attachment"
        private const val DEFAULT_MIME = "application/octet-stream"
    }
}

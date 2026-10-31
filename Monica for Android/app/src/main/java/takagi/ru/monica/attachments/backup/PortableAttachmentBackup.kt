package takagi.ru.monica.attachments.backup

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.io.OutputStream

/**
 * Portable WebDAV attachment backup format.
 *
 * Legacy attachment backup copied encrypted files from `secure_attachments` and the
 * device-wrapped CEK. That works only on the same security context. This format
 * stores attachment plaintext bytes inside the already encrypted WebDAV zip, and
 * restore writes them through [AttachmentStorage] so the target device receives
 * a fresh localPath + wrappedCek pair.
 */
object PortableAttachmentBackup {
    const val DIR_NAME = "attachments_portable"
    const val MANIFEST_NAME = "attachments_portable.json"
    const val MANIFEST_ENTRY = "$DIR_NAME/$MANIFEST_NAME"

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Manifest(
        val version: Int = 2,
        val entries: List<Entry> = emptyList()
    )

    @Serializable
    data class Entry(
        val parentPasswordId: Long? = null,
        val parentSecureItemId: Long? = null,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256Hex: String?,
        val payloadPath: String,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        fun isValid(): Boolean {
            val hasPassword = parentPasswordId?.let { it > 0L } == true
            val hasSecureItem = parentSecureItemId?.let { it > 0L } == true
            return hasPassword.xor(hasSecureItem) && payloadPath.isNotBlank() && fileName.isNotBlank()
        }
    }

    data class ExportResult(
        val payloads: List<Payload>
    )

    data class Payload(
        val entryName: String,
        val entry: Entry,
        val attachment: Attachment
    )

    data class RestorePlan(
        val entries: List<Entry> = emptyList(),
        val payloads: Map<String, File> = emptyMap(),
        val temporaryFiles: java.io.Closeable? = null,
    ) : java.io.Closeable {
        val isNotEmpty: Boolean
            get() = entries.isNotEmpty()

        override fun close() {
            try { payloads.values.distinct().forEach { it.delete() } }
            finally { temporaryFiles?.close() }
        }
    }

    fun decodeManifest(text: String): Manifest {
        if (text.isBlank()) return Manifest()
        return runCatching { json.decodeFromString<Manifest>(text) }
            .getOrElse { Manifest() }
    }

    fun encodeManifest(entries: List<Entry>): String =
        json.encodeToString(Manifest(entries = entries))

    suspend fun export(
        context: Context,
        attachments: List<Attachment>
    ): ExportResult = withContext(Dispatchers.IO) {
        val payloads = mutableListOf<Payload>()

        attachments.forEach { attachment ->
            val path = attachment.localPath
            val wrapped = attachment.wrappedCek
            if (attachment.sourceEnum != AttachmentSource.LOCAL ||
                path.isNullOrBlank() ||
                wrapped.isNullOrBlank()
            ) {
                return@forEach
            }
            if (attachment.owner == null) return@forEach

            val payloadName = Base64.encodeToString(
                path.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            ) + ".bin"
            val payloadPath = "$DIR_NAME/$payloadName"
            val entry = Entry(
                parentPasswordId = attachment.parentPasswordId,
                parentSecureItemId = attachment.parentSecureItemId,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sha256Hex = attachment.sha256Hex,
                payloadPath = payloadPath,
                createdAt = attachment.createdAt,
                updatedAt = attachment.updatedAt
            )
            payloads += Payload(payloadPath, entry, attachment)
        }

        ExportResult(payloads = payloads)
    }

    suspend fun writePayload(
        context: Context,
        payload: Payload,
        output: OutputStream
    ): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val path = payload.attachment.localPath ?: return@withContext false
        val wrapped = payload.attachment.wrappedCek ?: return@withContext false
        val storage = AttachmentStorage(app)
        val keyVault = AttachmentKeyVault(SecurityManager(app))
        val cek = runCatching { keyVault.unwrap(wrapped) }.getOrNull() ?: return@withContext false
        try {
            storage.openDecryptedStream(path, cek).use { input ->
                input.copyTo(output)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            cek.fill(0)
        }
    }

    suspend fun materialize(
        context: Context,
        entry: Entry,
        payloadFile: File,
        mappedParentId: Long,
        now: Long = System.currentTimeMillis()
    ): Attachment = materialize(
        context = context,
        entry = entry,
        payloadFile = payloadFile,
        mappedOwner = AttachmentOwner.password(mappedParentId),
        now = now
    )

    suspend fun materialize(
        context: Context,
        entry: Entry,
        payloadFile: File,
        mappedOwner: AttachmentOwner,
        now: Long = System.currentTimeMillis()
    ): Attachment = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val storage = AttachmentStorage(app)
        val keyVault = AttachmentKeyVault(SecurityManager(app))
        val blob = payloadFile.inputStream().buffered().use { storage.writeEncrypted(it) }
        val wrapped = try {
            keyVault.wrap(blob.cek)
        } catch (e: Throwable) {
            runCatching { storage.delete(blob.relativePath) }
            throw e
        } finally {
            blob.cek.fill(0)
        }

        Attachment(
            id = 0,
            parentPasswordId = mappedOwner.passwordId,
            parentSecureItemId = mappedOwner.secureItemId,
            source = AttachmentSource.LOCAL.name,
            fileName = entry.fileName,
            mimeType = entry.mimeType,
            sizeBytes = blob.sizeBytes.takeIf { it > 0 } ?: entry.sizeBytes,
            sha256Hex = blob.sha256Hex,
            wrappedCek = wrapped,
            localPath = blob.relativePath,
            downloadState = AttachmentDownloadState.DOWNLOADED.name,
            createdAt = entry.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now
        )
    }
}

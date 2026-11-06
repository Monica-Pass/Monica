package takagi.ru.monica.data.dedup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.security.SecurityManager

/** Only ciphertext is copied to recovery storage. Comparison MACs live for one scan only. */
internal class SingleVaultDedupLocalAssets(private val context: Context) {
    data class Evidence(val row: Attachment, val ciphertextHash: String, val comparisonMac: String)
    data class Backup(val attachmentId: Long, val fileName: String, val ciphertextHash: String)
    private val sourceRoot get() = File(context.filesDir, "secure_attachments").canonicalFile
    private val recoveryRoot get() = File(context.noBackupFilesDir, "single_vault_dedup_assets").canonicalFile
    private val security = SecurityManager(context)
    private val reader = LocalAttachmentExecutor(context, AttachmentStorage(context), AttachmentKeyVault(security))

    suspend fun inspect(row: Attachment, macKey: ByteArray, checkActive: () -> Unit): Evidence {
        require(row.source == "LOCAL" && row.owner != null && !row.wrappedCek.isNullOrEmpty())
        require(security.looksLikeMonicaCiphertext(requireNotNull(row.wrappedCek)))
        val file = sourceFile(requireNotNull(row.localPath))
        val before = hash(file, checkActive)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        var length = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { checkActive(); mac.update(value.toByte()); length++ }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                checkActive(); mac.update(bytes, offset, count); length += count
                check(length <= MAX_BLOB_SIZE)
            }
        }
        // Directly read the captured ciphertext, including soft-deleted attachments. The
        // facade intentionally rejects deleted rows and may query a different database.
        reader.openDecrypted(row).use { input ->
            val buffer = ByteArray(64 * 1024)
            try { while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
            } } finally { buffer.fill(0) }
        }
        check(length == row.sizeBytes && hash(file, checkActive) == before)
        return Evidence(row, before, hex(mac.doFinal()))
    }

    fun verify(evidence: Evidence, checkActive: () -> Unit) {
        check(hash(sourceFile(requireNotNull(evidence.row.localPath)), checkActive) == evidence.ciphertextHash)
    }

    fun backup(batchId: String, evidence: List<Evidence>, checkActive: () -> Unit): List<Backup> {
        val dir = batchDirectory(batchId)
        check(dir.isDirectory || dir.mkdirs())
        val created = mutableListOf<Backup>()
        try { evidence.forEach { item ->
            checkActive()
            val name = "${UUID.randomUUID()}.enc"
            val target = File(dir, name)
            created += Backup(item.row.id, name, item.ciphertextHash)
            copy(sourceFile(requireNotNull(item.row.localPath)), target, checkActive)
            check(hash(target, checkActive) == item.ciphertextHash)
        }
            // The synced file contents are not enough: their directory entries and newly
            // created ancestors must reach the filesystem before Room may commit tombstones.
            syncDirectory(dir)
            syncDirectory(recoveryRoot)
            syncDirectory(context.noBackupFilesDir)
            syncDirectory(requireNotNull(context.noBackupFilesDir.parentFile))
            checkActive()
            return created
        } catch (error: Throwable) { discardBackup(batchId, created); throw error }
    }

    /** A fresh file is prepared before the DB transaction; no existing blob is overwritten. */
    fun prepareRestore(batchId: String, backups: List<Backup>, checkActive: () -> Unit): Map<Long, String> {
        check(sourceRoot.isDirectory || sourceRoot.mkdirs())
        val created = mutableMapOf<Long, String>()
        try { backups.forEach { item ->
            val archived = child(batchDirectory(batchId), item.fileName)
            check(hash(archived, checkActive) == item.ciphertextHash)
            val name = "${UUID.randomUUID()}.enc"
            val target = File(sourceRoot, name)
            check(item.attachmentId !in created)
            created[item.attachmentId] = name
            copy(archived, target, checkActive)
            check(hash(target, checkActive) == item.ciphertextHash)
        }
            syncDirectory(sourceRoot)
            syncDirectory(context.filesDir)
            syncDirectory(requireNotNull(context.filesDir.parentFile))
            checkActive()
            return created
        } catch (error: Throwable) { discardPrepared(created.values); throw error }
    }

    fun discardBackup(batchId: String, backups: List<Backup>) {
        val dir = batchDirectory(batchId)
        backups.forEach { child(dir, it.fileName).delete() }
        if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
    }

    /** Caller has checked no committed attachment references these freshly-created names. */
    fun discardPrepared(names: Collection<String>) { names.forEach { sourceFile(it).delete() } }

    private fun sourceFile(name: String): File = child(sourceRoot, name)
    private fun child(parent: File, name: String): File {
        require(name.endsWith(".enc") && UUID.fromString(name.removeSuffix(".enc")).toString() + ".enc" == name)
        return File(parent, name).also { require(it.canonicalFile.parentFile == parent.canonicalFile && it.canonicalFile == it.absoluteFile) }
    }
    private fun batchDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(recoveryRoot, id).also { require(it.canonicalFile == it.absoluteFile) }
    }
    private fun hash(file: File, checkActive: () -> Unit): String {
        require(file.isFile && file.length() in 1..MAX_BLOB_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count; check(total <= MAX_BLOB_SIZE)
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun copy(source: File, destination: File, checkActive: () -> Unit) {
        check(destination.createNewFile())
        FileOutputStream(destination).use { output ->
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count; check(total <= MAX_BLOB_SIZE)
                    output.write(buffer, 0, count)
                }
            }
            output.fd.sync()
        }
    }
    /** Failure propagates to the caller; unsupported filesystems never authorize cleanup. */
    internal fun syncDirectory(directory: File) {
        val canonical = directory.canonicalFile
        require(canonical.isDirectory)
        val descriptor = Os.open(canonical.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    companion object { private const val MAX_BLOB_SIZE = 512L * 1024 * 1024 }
}

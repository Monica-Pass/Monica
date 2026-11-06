package takagi.ru.monica.data.dedup

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import takagi.ru.monica.security.SecurityManager

/** Room holds only the small encrypted receipt. Files contain authenticated ciphertext, never JSON. */
internal class SingleVaultDedupPayloadStore(
    private val root: File,
    private val seal: (String) -> String,
    private val open: (String) -> String,
    private val syncDirectory: (File) -> Unit,
) {
    constructor(context: Context, security: SecurityManager) : this(
        File(context.noBackupFilesDir.canonicalFile, DIRECTORY), security::encryptTimelineSnapshot, security::decryptTimelineSnapshot,
        { directory ->
            val fd = android.system.Os.open(directory.path,
                android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
            try {
                check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode))
                android.system.Os.fsync(fd)
            } finally { android.system.Os.close(fd) }
        })

    fun write(batchId: String, sourceKey: String, plaintext: String, checkActive: () -> Unit): String {
        require(plaintext.toByteArray(Charsets.UTF_8).size <= MAX_PLAIN)
        checkActive()
        val ciphertext = seal(plaintext).toByteArray(Charsets.UTF_8)
        require(ciphertext.size <= MAX_SEALED)
        require(open(ciphertext.toString(Charsets.UTF_8)) == plaintext)
        prepareDirectory()
        val target = file(batchId)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        val staging = File.createTempFile(".preparing-", ".sealed", root)
        try {
            FileOutputStream(staging).use { it.write(ciphertext); it.flush(); it.fd.sync() }
            require(digest(readBounded(staging, ciphertext.size)) == digest(ciphertext))
            checkActive()
            require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(root)
            val receipt = buildJsonObject {
                put("kind", KIND); put("version", 1); put("batchId", batchId); put("source", sourceKey)
                put("bytes", ciphertext.size); put("sha256", digest(ciphertext))
            }.toString()
            val sealedReceipt = seal(receipt)
            require(sealedReceipt.toByteArray(Charsets.UTF_8).size <= MAX_RECEIPT)
            require(read(batchId, sourceKey, sealedReceipt) == plaintext)
            return sealedReceipt
        } finally { if (staging.exists()) check(staging.delete()) }
    }

    fun read(batchId: String, sourceKey: String, sealedReceipt: String): String {
        require(sealedReceipt.toByteArray(Charsets.UTF_8).size <= MAX_RECEIPT)
        val manifest = SingleVaultDedupProjectBuilder.strictJson(open(sealedReceipt)).jsonObject
        require(manifest.keys == setOf("kind", "version", "batchId", "source", "bytes", "sha256"))
        require(manifest["kind"] == JsonPrimitive(KIND) && manifest["version"] == JsonPrimitive(1))
        require(manifest["batchId"] == JsonPrimitive(batchId) && manifest["source"] == JsonPrimitive(sourceKey))
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) && root.canonicalFile == root.absoluteFile)
        val size = manifest.getValue("bytes").jsonPrimitive.int
        require(size in 1..MAX_SEALED)
        val bytes = readBounded(file(batchId), size)
        require(digest(bytes) == manifest.getValue("sha256").jsonPrimitive.content)
        return open(bytes.toString(Charsets.UTF_8)).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_PLAIN) }
    }

    private fun prepareDirectory() {
        require(root.canonicalFile == root.absoluteFile)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(root.toPath())
            syncDirectory(requireNotNull(root.parentFile))
        }
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, "$id.sealed").also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }

    private fun readBounded(file: File, expectedSize: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(Files.size(file.toPath()) == expectedSize.toLong())
        val output = ByteArrayOutputStream(expectedSize)
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= expectedSize)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().also { require(it.size == expectedSize) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val DIRECTORY = "single-vault-dedup-recovery"
        const val KIND = "dedup-encrypted-file"
        const val MAX_PLAIN = 32 * 1024 * 1024
        private const val MAX_SEALED = 64 * 1024 * 1024
        private const val MAX_RECEIPT = 16 * 1024
    }
}

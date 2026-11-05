package takagi.ru.monica.attachments.executor

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto
import takagi.ru.monica.bitwarden.api.AttachmentUploadRequest
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Durable upload intent. Its payload is the exact Bitwarden ciphertext, never a plaintext file.
 * No timeout, logout, failed request, or malformed record authorizes deleting either file.
 */
class BitwardenAttachmentRecoveryStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)
    private val security = SecurityManager(context.applicationContext)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable
    data class Binding(
        val vaultId: Long,
        val accountKey: String,
        val userId: String?,
        val serverUrl: String,
        val apiUrl: String,
        val identityUrl: String,
        val transportRevision: String,
        val transportSecretRef: String?,
        val cipherId: String,
        val ownerKind: String,
        val ownerId: Long,
    ) {
        override fun toString() = "AttachmentRecoveryBinding(redacted)"
    }

    @Serializable
    enum class Phase { PREPARED, ALLOCATING, ALLOCATED, UPLOADING, UPLOADED, ADOPTED, DELETING, REMOVED }

    @Serializable
    data class Record(
        val operationId: String,
        val binding: Binding,
        val localAttachmentId: Long?,
        val fileName: String,
        val mimeType: String,
        val plainSize: Long,
        val plainSha256: String,
        val encryptedSize: Long,
        val encryptedSha256: String,
        val requestKey: String,
        val requestFileName: String,
        val phase: Phase = Phase.PREPARED,
        val remoteId: String? = null,
        val uploadType: Int? = null,
        val publishedLocalId: Long? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val version: Int = 1,
    ) {
        fun request() = AttachmentUploadRequest(requestKey, requestFileName, encryptedSize.toString())
        override fun toString() = "AttachmentRecoveryRecord(redacted)"
    }

    enum class RecoveryPlan { NOT_SENT, RECONCILE, RESERVED, VERIFY_UPLOAD, PUBLISHED, VERIFY_DELETION, REMOVED }

    fun planRecovery(record: Record): RecoveryPlan = when (record.phase) {
        Phase.PREPARED -> RecoveryPlan.NOT_SENT
        Phase.ALLOCATING -> RecoveryPlan.RECONCILE
        Phase.ALLOCATED -> RecoveryPlan.RESERVED
        Phase.UPLOADING, Phase.UPLOADED -> RecoveryPlan.VERIFY_UPLOAD
        Phase.ADOPTED -> RecoveryPlan.PUBLISHED
        Phase.DELETING -> RecoveryPlan.VERIFY_DELETION
        Phase.REMOVED -> RecoveryPlan.REMOVED
    }

    suspend fun prepare(
        binding: Binding, localAttachmentId: Long?, fileName: String, mimeType: String,
        plainSize: Long, plainSha256: String, request: AttachmentUploadRequest,
        ciphertext: File, requireCurrent: () -> Unit,
    ): Record = withContext(Dispatchers.IO) {
        mutex.withLock {
            current(requireCurrent)
            validateBinding(binding)
            require(localAttachmentId == null || localAttachmentId > 0)
            // A failed/uncertain earlier request must never become a second allocation.
            if (localAttachmentId != null) check(listAllUnlocked().none {
                it.binding.vaultId == binding.vaultId && it.localAttachmentId == localAttachmentId &&
                    it.binding.ownerKind == binding.ownerKind && it.binding.ownerId == binding.ownerId &&
                    it.phase != Phase.ADOPTED && it.phase != Phase.REMOVED
            }) { "Attachment recovery must finish before another upload" }
            val size = ciphertext.length()
            require(size in 1..BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES)
            require(request.fileSize == size.toString())
            val id = UUID.randomUUID().toString()
            val target = path(id, "payload")
            // Sync the immutable encrypted bytes before publishing metadata or sending HTTP.
            FileOutputStream(target).use { out ->
                ciphertext.inputStream().use { input -> input.copyTo(out) }
                out.fd.sync()
            }
            current(requireCurrent)
            check(target.length() == size)
            val record = Record(id, binding, localAttachmentId, fileName, mimeType, plainSize,
                plainSha256, size, sha256(target), request.key, request.fileName)
            write(record, requireCurrent)
            record
        }
    }

    suspend fun list(binding: Binding): List<Record> = withContext(Dispatchers.IO) {
        mutex.withLock { current {}; listUnlocked(binding) }
    }

    suspend fun listForVault(vault: BitwardenVault): List<Record> = withContext(Dispatchers.IO) {
        mutex.withLock { listAllUnlocked().filter { matchesVault(it.binding, vault) } }
    }

    /** Queue discovery only. A changed binding may be reported generically, never used for HTTP. */
    suspend fun listIntentsForVaultId(vaultId: Long): List<Record> = withContext(Dispatchers.IO) {
        require(vaultId > 0)
        mutex.withLock { listAllUnlocked().filter { it.binding.vaultId == vaultId } }
    }

    suspend fun load(binding: Binding, operationId: String): Record = withContext(Dispatchers.IO) {
        mutex.withLock { read(operationId).also { check(matchesBinding(it.binding, binding)) } }
    }

    suspend fun findPending(binding: Binding, localAttachmentId: Long): Record? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val records = listAllUnlocked().filter {
                it.binding.vaultId == binding.vaultId && it.localAttachmentId == localAttachmentId &&
                    it.binding.ownerKind == binding.ownerKind && it.binding.ownerId == binding.ownerId &&
                    it.phase !in setOf(Phase.REMOVED, Phase.ADOPTED)
            }
            check(records.size <= 1) { "Conflicting attachment recovery records" }
            records.singleOrNull()?.also { check(matchesBinding(it.binding, binding)) { "Attachment recovery account or transport changed" } }
        }
    }

    suspend fun advance(
        binding: Binding, operationId: String, expected: Phase, phase: Phase,
        remoteId: String? = null, uploadType: Int? = null, requireCurrent: () -> Unit,
    ): Record = withContext(Dispatchers.IO) {
        mutex.withLock {
            current(requireCurrent)
            val before = read(operationId).also { check(matchesBinding(it.binding, binding) && it.phase == expected) }
            check(validTransition(expected, phase))
            val after = before.copy(phase = phase, remoteId = remoteId ?: before.remoteId,
                uploadType = uploadType ?: before.uploadType)
            check(before.remoteId == null || before.remoteId == after.remoteId)
            if (phase in setOf(Phase.ALLOCATED, Phase.UPLOADING, Phase.UPLOADED, Phase.DELETING)) {
                require(!after.remoteId.isNullOrBlank())
            }
            write(after, requireCurrent)
            after
        }
    }

    /** Caller first commits and verifies the exact Room reference; a late cancellation is adoption,
     * never permission to roll back that row or its remote object. Payload removal is separate.
     */
    suspend fun markPublished(
        binding: Binding, remoteId: String, localRowId: Long, requireCurrent: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            current(requireCurrent)
            require(localRowId > 0)
            val matches = listUnlocked(binding).filter { it.remoteId == remoteId && it.phase != Phase.REMOVED }
            check(matches.size <= 1) { "Conflicting attachment publication receipts" }
            val before = matches.singleOrNull() ?: return@withLock
            check(before.phase == Phase.UPLOADED || before.phase == Phase.ADOPTED)
            check(before.publishedLocalId == null || before.publishedLocalId == localRowId)
            write(before.copy(phase = Phase.ADOPTED, publishedLocalId = localRowId), requireCurrent)
            // Keep the small adoption receipt so stale rollback/retry can never delete this object.
            current(requireCurrent)
            path(before.operationId, "payload").let { if (it.exists()) check(it.delete()) }
        }
    }

    suspend fun isPublished(binding: Binding, remoteId: String): Boolean = list(binding).any {
        it.remoteId == remoteId && it.phase == Phase.ADOPTED
    }

    /** Raw encrypted metadata only: never infer ownership from a decrypted name or file size. */
    suspend fun protectedRemoteIds(binding: Binding, remote: List<CipherAttachmentApiData>, namesAreDecoded: Boolean = false): Set<String> {
        val pending = list(binding).filter { it.phase != Phase.ADOPTED && it.phase != Phase.REMOVED }
        return pending.flatMap { record ->
            listOfNotNull(record.remoteId) + remote.filter {
                if (namesAreDecoded) it.id.isNotBlank() && it.key == record.requestKey &&
                    it.fileName == record.fileName && it.size == record.encryptedSize.toString()
                else matchesRequest(record, it)
            }.map { it.id }
        }.toSet()
    }

    suspend fun ciphertext(binding: Binding, operationId: String): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = read(operationId).also { check(matchesBinding(it.binding, binding)) }
            path(operationId, "payload").also {
                check(it.isFile && it.length() == record.encryptedSize && sha256(it) == record.encryptedSha256) {
                    "Attachment recovery ciphertext unavailable"
                }
            }
        }
    }

    private fun listUnlocked(binding: Binding): List<Record> = listAllUnlocked().filter { matchesBinding(it.binding, binding) }

    private fun listAllUnlocked(): List<Record> {
        current {}
        if (!directory.exists()) return emptyList()
        return directory.listFiles().orEmpty().filter { it.name.matches(RECORD_NAME) }
            .map { it.name.substringBefore(".record") }.distinct().map { id ->
            // Do not silently skip a corrupt record and then allocate/upload a duplicate.
            read(id)
        }
    }

    private fun read(operationId: String): Record {
        current {}
        val file = AtomicFile(path(operationId, "record"))
        val sealed = file.openRead().use { input ->
            val bytes = input.readBytesBounded(MAX_METADATA_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        check(sealed.startsWith("MDK|"))
        val record = json.decodeFromString<Record>(security.decryptData(sealed))
        check(record.version == 1 && record.operationId == operationId)
        validateBinding(record.binding)
        check(record.encryptedSize in 1..BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES)
        check(record.encryptedSha256.matches(SHA256) && record.plainSha256.matches(SHA256))
        check(record.localAttachmentId == null || record.localAttachmentId > 0)
        current {}
        return record
    }

    private fun write(record: Record, requireCurrent: () -> Unit) {
        current(requireCurrent)
        val plain = json.encodeToString(record)
        check(plain.toByteArray().size <= MAX_METADATA_BYTES / 2)
        val sealed = security.encryptData(plain)
        check(sealed.startsWith("MDK|") && security.decryptData(sealed) == plain)
        val file = AtomicFile(path(record.operationId, "record"))
        val out = file.startWrite()
        try {
            out.write(sealed.toByteArray(Charsets.UTF_8))
            current(requireCurrent)
            file.finishWrite(out)
        } catch (error: Throwable) { file.failWrite(out); throw error }
    }

    private fun path(operationId: String, suffix: String): File {
        require(UUID.fromString(operationId).toString() == operationId)
        if (!directory.exists()) check(directory.mkdirs())
        check(directory.canonicalFile == File(directory.parentFile.canonicalFile, DIRECTORY))
        return File(directory, "$operationId.$suffix").also {
            check(it.canonicalFile == File(directory.canonicalFile, it.name))
        }
    }

    private fun current(requireCurrent: () -> Unit) {
        check(SessionManager.isUnlocked.value) { "Attachment recovery session unavailable" }
        requireCurrent()
    }

    companion object {
        private const val DIRECTORY = "bitwarden_attachment_recovery_v1"
        private const val MAX_METADATA_BYTES = 128 * 1024
        private val mutex = Mutex()
        private val RECORD_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.record(?:\\.bak|\\.new)?")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun binding(vault: BitwardenVault, cipherId: String, owner: AttachmentOwner): Binding = Binding(
            vault.id, vault.accountKey, vault.userId, vault.serverUrl, vault.apiUrl, vault.identityUrl,
            vault.transportRevision, vault.transportSecretRef, cipherId, owner.kind.name, owner.id)

        fun matchesVault(binding: Binding, vault: BitwardenVault): Boolean =
            binding.accountKey.isNotBlank() && vault.accountKey.isNotBlank() &&
                binding.vaultId == vault.id && binding.userId == vault.userId &&
                binding.serverUrl == vault.serverUrl && binding.apiUrl == vault.apiUrl && binding.identityUrl == vault.identityUrl &&
                binding.transportRevision == vault.transportRevision && binding.transportSecretRef == vault.transportSecretRef

        /** accountKey is a projection alias that sync may promote from email to server user ID.
         * Keep its original nonblank value in the receipt; only stable identity/scope fields bind recovery.
         */
        internal fun matchesBinding(recorded: Binding, current: Binding): Boolean =
            recorded.accountKey.isNotBlank() && current.accountKey.isNotBlank() &&
                recorded.vaultId == current.vaultId && recorded.userId == current.userId &&
                recorded.serverUrl == current.serverUrl && recorded.apiUrl == current.apiUrl &&
                recorded.identityUrl == current.identityUrl && recorded.transportRevision == current.transportRevision &&
                recorded.transportSecretRef == current.transportSecretRef && recorded.cipherId == current.cipherId &&
                recorded.ownerKind == current.ownerKind && recorded.ownerId == current.ownerId

        internal fun validateBinding(binding: Binding) {
            require(binding.vaultId > 0 && binding.ownerId > 0 && binding.accountKey.isNotBlank())
            require(binding.cipherId.isNotBlank() && binding.ownerKind in setOf("PASSWORD", "SECURE_ITEM"))
            require(binding.apiUrl.isNotBlank() && binding.serverUrl.isNotBlank() && binding.identityUrl.isNotBlank())
        }

        internal fun validTransition(from: Phase, to: Phase): Boolean = when (from) {
            Phase.PREPARED -> to == Phase.ALLOCATING
            Phase.ALLOCATING -> to == Phase.ALLOCATED || to == Phase.UPLOADED
            Phase.ALLOCATED -> to == Phase.UPLOADING || to == Phase.UPLOADED || to == Phase.DELETING
            Phase.UPLOADING -> to == Phase.UPLOADED
            Phase.UPLOADED, Phase.ADOPTED, Phase.REMOVED -> false
            Phase.DELETING -> to == Phase.REMOVED
        }

        internal fun matchesRequest(record: Record, remote: CipherAttachmentApiData): Boolean =
            remote.id.isNotBlank() && remote.key == record.requestKey &&
                remote.fileName == record.requestFileName && remote.size == record.encryptedSize.toString()

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count == -1) break
                check(output.size() + count <= limit)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

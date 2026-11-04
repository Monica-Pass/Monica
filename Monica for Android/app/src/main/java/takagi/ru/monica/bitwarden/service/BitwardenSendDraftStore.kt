package takagi.ru.monica.bitwarden.service

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import takagi.ru.monica.attachments.crypto.AttachmentCryptoStreams
import takagi.ru.monica.attachments.facade.AttachmentSizeValidator
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Encrypted local drafts. Never creates a remote Send or automatically resubmits one. */
class BitwardenSendDraftStore(context: Context) {
    private val context = context.applicationContext
    private val database = PasswordDatabase.getDatabase(context)
    private val security = SecurityManager(context)
    private val keyVault = AttachmentKeyVault(security)
    private val directory = File(context.filesDir, "bitwarden_send_drafts_v1")
    // Dynamic defaults such as updatedAt must be persisted, never regenerated during a read.
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val repository get() = BitwardenRepository.getInstance(context)

    @Serializable data class Draft(
        val id: String, val vaultId: Long, val account: BitwardenSendAccountIdentity,
        val draft: BitwardenSendDraft, val original: BitwardenSend? = null,
        val file: FilePayload? = null, val type: Int = BitwardenSend.TYPE_TEXT, val accessLimitInput: String? = null, val updatedAt: Long = System.currentTimeMillis(),
        val submittedKey: String? = null, val submissionUncertain: Boolean = false,
        val preparedRequest: JsonObject? = null, val fileUploadConfirmed: Boolean = false,
        val version: Int = 1
    )
    @Serializable data class FilePayload(val id: String, val name: String, val size: Long,
        val sha256: String, val wrappedKey: String)
    private data class Session(val vault: BitwardenVault, val generation: Long, val lock: Long)

    private fun path(vaultId: Long, id: String, suffix: String): File {
        require(vaultId > 0)
        require(UUID.fromString(id).toString() == id)
        if (!directory.exists()) check(directory.mkdirs())
        check(directory.canonicalFile == File(context.filesDir.canonicalFile, "bitwarden_send_drafts_v1"))
        return File(directory, "$vaultId-$id.$suffix").also { check(it.canonicalFile == File(directory.canonicalFile, it.name)) }
    }

    private suspend fun session(vaultId: Long): Session {
        val vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
        return Session(vault, repository.getVaultSessionGeneration(vaultId), SessionManager.lockGeneration.value).also { current(it) }
    }
    private suspend fun current(session: Session) {
        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == session.lock &&
            repository.isVaultUnlocked(session.vault.id) && repository.getVaultSessionGeneration(session.vault.id) == session.generation)
        val vault = database.bitwardenVaultDao().getVaultById(session.vault.id)
        check(vault != null && !vault.isLocked && BitwardenSendAccountIdentity.from(vault) == BitwardenSendAccountIdentity.from(session.vault))
    }

    suspend fun save(vaultId: Long, draft: BitwardenSendDraft, original: BitwardenSend? = null,
        existingId: String? = null, selectedUri: Uri? = null, selectedName: String? = null,
        keepExistingFile: Boolean = true, type: Int = BitwardenSend.TYPE_TEXT, accessLimitInput: String? = null): Draft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            require(original == null || original.vaultId == vaultId)
            val previous = existingId?.let { read(it, session) }
            check(previous?.submissionUncertain != true) { "Reconcile the prior submission before editing" }
            val id = previous?.id ?: UUID.randomUUID().toString()
            val newFile = selectedUri?.let { encryptFile(it, requireNotNull(selectedName), session) }
            val file = newFile ?: previous?.file?.takeIf { keepExistingFile }
            val value = Draft(id, vaultId, BitwardenSendAccountIdentity.from(session.vault), draft, original, file, type, accessLimitInput)
            try { write(value, session) }
            catch (error: Throwable) { newFile?.let { path(vaultId, it.id, "payload").delete() }; throw error }
            if (previous?.file != null && previous.file.id != file?.id) path(vaultId, previous.file.id, "payload").delete()
            value
        }
    }

    data class Listing(val drafts: List<Draft>, val unavailable: Int)
    suspend fun list(vaultId: Long): List<Draft> = listDetailed(vaultId).drafts
    suspend fun listDetailed(vaultId: Long): Listing = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            var unavailable = 0
            val ids = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
                .filter { it.startsWith("$vaultId-") && it.endsWith(".draft") }
                .map { it.removePrefix("$vaultId-").removeSuffix(".draft") }.distinct()
            val drafts = ids.mapNotNull { id ->
                try { read(id, session) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { current(session); unavailable++; null }
            }.sortedByDescending { it.updatedAt }
            current(session)
            Listing(drafts, unavailable)
        }
    }

    suspend fun load(vaultId: Long, id: String): Draft = withContext(Dispatchers.IO) {
        mutex.withLock { read(id, session(vaultId)) }
    }

    private suspend fun read(id: String, session: Session): Draft {
        current(session)
        val file = AtomicFile(path(session.vault.id, id, "draft"))
        check(file.baseFile.length() <= MAX_METADATA_BYTES)
        val sealed = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                check(output.size() + count <= MAX_METADATA_BYTES)
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        check(sealed.startsWith("MDK|"))
        val plain = security.decryptData(sealed)
        val value = json.decodeFromString<Draft>(plain)
        check(value.version == 1 && value.id == id && value.vaultId == session.vault.id &&
            value.account == BitwardenSendAccountIdentity.from(session.vault))
        check(value.original == null || value.original.vaultId == value.vaultId)
        check(value.file == null || (value.file.size in 0..AttachmentSizeValidator.HARD_LIMIT_BYTES &&
            value.file.sha256.matches(Regex("[0-9a-f]{64}"))))
        check(value.submissionUncertain == (value.preparedRequest != null))
        check(value.preparedRequest == null || value.submittedKey == BitwardenSendEdit.string(value.preparedRequest, "key"))
        current(session)
        // The former default-skipping encoder could omit this dynamic timestamp. Use the owned
        // metadata file's stable time only after all decryption/account/lock checks have passed.
        // This affects list ordering only and does not rewrite legacy or unreadable files.
        return if ((json.parseToJsonElement(plain) as JsonObject).containsKey("updatedAt")) value
            else value.copy(updatedAt = file.baseFile.lastModified().coerceAtLeast(0L))
    }

    private suspend fun write(value: Draft, session: Session) {
        current(session)
        val plain = json.encodeToString(value)
        check(plain.toByteArray().size <= 2 * 1024 * 1024)
        val sealed = security.encryptData(plain)
        check(sealed.startsWith("MDK|") && security.decryptData(sealed) == plain)
        val file = AtomicFile(path(session.vault.id, value.id, "draft"))
        val output = file.startWrite()
        try {
            output.write(sealed.toByteArray(Charsets.UTF_8))
            current(session)
            file.finishWrite(output)
        } catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private suspend fun encryptFile(uri: Uri, name: String, session: Session): FilePayload {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        val target = path(session.vault.id, id, "payload")
        val key = AttachmentCryptoStreams.newCek()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    AttachmentCryptoStreams.writeChunkedEncrypted(input, output, key) { buffer, count ->
                        size += count
                        check(size <= AttachmentSizeValidator.HARD_LIMIT_BYTES)
                        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == session.lock &&
                            repository.getVaultSessionGeneration(session.vault.id) == session.generation)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Selected file is unavailable")
            current(session)
            return FilePayload(id, name, size, digest.digest().joinToString("") { "%02x".format(it) }, keyVault.wrap(key).also { check(it.startsWith("MDK|")) })
        } catch (error: Throwable) { target.delete(); throw error }
        finally { key.fill(0) }
    }

    suspend fun openFile(vaultId: Long, draftId: String): InputStream = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            val value = read(draftId, session)
            val file = requireNotNull(value.file)
            check(file.wrappedKey.startsWith("MDK|"))
            val key = keyVault.unwrap(file.wrappedKey)
            try {
                val input = path(session.vault.id, file.id, "payload").inputStream().buffered()
                val decrypted = try {
                    val magic = ByteArray(AttachmentCryptoStreams.chunkedMagicSize())
                    java.io.DataInputStream(input).readFully(magic)
                    check(AttachmentCryptoStreams.isChunkedMagic(magic))
                    AttachmentCryptoStreams.chunkedDecryptingStream(input, key)
                }
                    catch (error: Throwable) { input.close(); throw error }
                key.fill(0)
                object : FilterInputStream(decrypted) {
                    private val digest = MessageDigest.getInstance("SHA-256")
                    private var size = 0L
                    private var verified = false
                    private fun active() {
                        // InputStream is blocking; also recheck persisted endpoint/account identity per read.
                        kotlinx.coroutines.runBlocking { current(session) }
                        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == session.lock &&
                            repository.isVaultUnlocked(vaultId) && repository.getVaultSessionGeneration(vaultId) == session.generation)
                    }
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        active()
                        val count = `in`.read(buffer, offset, length)
                        if (count > 0) { size += count; check(size <= file.size); digest.update(buffer, offset, count) }
                        if (count == -1 && !verified) {
                            check(size == file.size && digest.digest().joinToString("") { "%02x".format(it) } == file.sha256)
                            verified = true
                        }
                        active()
                        return count
                    }
                }
            } catch (error: Throwable) { key.fill(0); throw error }
        }
    }

    suspend fun markSubmitting(vaultId: Long, id: String, request: JsonObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            val value = read(id, session)
            check(!value.submissionUncertain)
            val key = requireNotNull(BitwardenSendEdit.string(request, "key")).also { require(it.isNotBlank()) }
            write(value.copy(submittedKey = key, submissionUncertain = true, preparedRequest = request,
                fileUploadConfirmed = false), session)
        }
    }
    suspend fun markFileUploadConfirmed(vaultId: Long, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            val value = read(id, session)
            check(value.submissionUncertain && value.preparedRequest != null && value.type == BitwardenSend.TYPE_FILE)
            write(value.copy(fileUploadConfirmed = true), session)
        }
    }
    suspend fun markKnownFailure(vaultId: Long, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            val value = read(id, session)
            write(value.copy(submittedKey = null, submissionUncertain = false, preparedRequest = null,
                fileUploadConfirmed = false), session)
        }
    }
    suspend fun discard(vaultId: Long, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = session(vaultId)
            val value = read(id, session)
            current(session)
            AtomicFile(path(session.vault.id, id, "draft")).delete()
            value.file?.let { val payload = path(vaultId, it.id, "payload"); check(!payload.exists() || payload.delete()) }
        }
    }
    /** Explicit account removal only; ordinary cache clearing never discards local drafts. */
    suspend fun discardVaultAfterRevocation(vaultId: Long) = withContext(Dispatchers.IO) {
        require(vaultId > 0)
        mutex.withLock {
            check(!repository.isVaultUnlocked(vaultId)) { "Revoke the account before deleting its drafts" }
            if (!directory.exists()) return@withLock
            check(directory.canonicalFile == File(context.filesDir.canonicalFile, "bitwarden_send_drafts_v1"))
            val ownedName = Regex("${vaultId}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:draft(?:\\.(?:bak|new))?|payload)")
            directory.listFiles().orEmpty().filter { ownedName.matches(it.name) }.forEach { file ->
                // Never follow links or recurse; unknown names and other accounts remain untouched.
                check(file.canonicalFile == File(directory.canonicalFile, file.name) && file.isFile)
                check(file.delete()) { "Cannot remove an owned Send draft" }
            }
        }
    }
    companion object { private val mutex = Mutex(); private const val MAX_METADATA_BYTES = 4 * 1024 * 1024 }
}

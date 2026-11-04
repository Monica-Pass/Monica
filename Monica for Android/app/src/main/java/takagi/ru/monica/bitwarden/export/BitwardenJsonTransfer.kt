package takagi.ru.monica.bitwarden.export

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.*
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.transfer.*
import java.io.ByteArrayOutputStream

data class BitwardenJsonPreview(val items: Int, val folders: Int, val requiresPassword: Boolean,
    val issues: List<BitwardenJsonIssue> = emptyList())
internal fun bitwardenJsonAccountFingerprint(vault: takagi.ru.monica.data.bitwarden.BitwardenVault): String =
    takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint(vault) + "\u0000" + vault.accountKey + "\u0000" +
        vault.canonicalEmail + "\u0000" + vault.eventsUrl.orEmpty()

class BitwardenJsonExportPermit private constructor(
    private val authorization: ExportAuthorization,
    private val sourceKey: String,
    private val appGeneration: Long,
    private val vaultGeneration: Long?,
    private val accountIdentity: String?,
) {
    @Volatile private var revoked = false
    internal fun clear() { revoked = true; authorization.clear() }
    internal suspend fun consume(context: Context, source: ImportDestination): Boolean =
        authorization.consume(source.key, SystemClock.elapsedRealtime()) && isCurrent(context, source)
    internal suspend fun isCurrent(context: Context, source: ImportDestination): Boolean {
        if (revoked || source.key != sourceKey || !SessionManager.isUnlocked.value || SessionManager.lockGeneration.value != appGeneration) return false
        val vaultId = source.bitwardenId ?: return accountIdentity == null
        val repository = BitwardenRepository.getInstance(context)
        val vault = PasswordDatabase.getDatabase(context).bitwardenVaultDao().getVaultById(vaultId) ?: return false
        return vault.isConnected && !vault.isLocked && repository.isVaultUnlocked(vaultId) &&
            repository.getVaultSessionGeneration(vaultId) == vaultGeneration && bitwardenJsonAccountFingerprint(vault) == accountIdentity
    }
    internal companion object {
        suspend fun grant(context: Context, source: ImportDestination, authenticatedGeneration: Long): BitwardenJsonExportPermit {
            check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == authenticatedGeneration)
            val repository = BitwardenRepository.getInstance(context)
            val generation = source.bitwardenId?.let(repository::getVaultSessionGeneration)
            val account = source.bitwardenId?.let { id ->
                check(repository.isVaultUnlocked(id))
                val vault = requireNotNull(PasswordDatabase.getDatabase(context).bitwardenVaultDao().getVaultById(id))
                check(vault.isConnected && !vault.isLocked)
                bitwardenJsonAccountFingerprint(vault)
            }
            check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == authenticatedGeneration)
            return BitwardenJsonExportPermit(ExportAuthorization().also { it.grant(source.key, SystemClock.elapsedRealtime()) },
                source.key, authenticatedGeneration, generation, account)
        }
    }
}

data class BitwardenJsonExportSummary(val items: Int, val folders: Int, val omittedAttachments: Int)

internal class BitwardenJsonTransfer(context: Context) {
    private val context = context.applicationContext
    private val db = PasswordDatabase.getDatabase(this.context)
    private val security = SecurityManager(this.context)
    private val repository = BitwardenRepository.getInstance(this.context)
    private fun epoch(): Long = SessionManager.lockGeneration.value.also { requireCurrent(it) }
    private fun requireCurrent(epoch: Long) {
        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == epoch) { "JSON transfer session changed" }
    }

    suspend fun previewImport(uri: Uri, password: String?): BitwardenJsonPreview = withContext(Dispatchers.IO) {
        val epoch = epoch()
        val bytes = read(uri, epoch)
        try {
            val encrypted = BitwardenJsonCodec.requiresPassword(bytes)
            if (encrypted && password == null) return@withContext BitwardenJsonPreview(0, 0, true)
            val document = try { BitwardenJsonCodec.decode(bytes, password) } catch (error: BitwardenJsonRejected) {
                requireCurrent(epoch)
                return@withContext BitwardenJsonPreview(0, 0, encrypted, error.issues)
            }
            currentCoroutineContext().ensureActive()
            val issues = BitwardenJsonLocalMapper.problems(document) + duplicatePasskeyProblems(document)
            requireCurrent(epoch)
            BitwardenJsonPreview(document.items.size, document.folders.size, encrypted, issues)
        } finally { bytes.fill(0) }
    }

    suspend fun importLocal(uri: Uri, password: String?, destination: ImportDestination,
        progress: TransferProgressReporter = TransferProgressReporter.None): ImportResultSummary = withContext(Dispatchers.IO) {
        if (destination != ImportDestination.Local) throw BitwardenJsonRejected(listOf(
            BitwardenJsonIssue(null, null, "destination", BitwardenJsonIssue.Reason.DESTINATION)))
        mutex.withLock {
            val epoch = epoch()
            val bytes = read(uri, epoch)
            val document = try { BitwardenJsonCodec.decode(bytes, password) } finally { bytes.fill(0) }
            currentCoroutineContext().ensureActive()
            val issues = BitwardenJsonLocalMapper.problems(document) + duplicatePasskeyProblems(document)
            if (issues.isNotEmpty()) throw BitwardenJsonRejected(issues)
            requireCurrent(epoch)
            val converted = document.items.mapIndexed { index, item ->
                currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                BitwardenJsonLocalMapper.fromJson(item.jsonObject, index, security::encryptData)
            }
            val protectedRefs = mutableSetOf<String>()
            var committed = false
            try {
                // Private-key material never enters Room. Publication and all row writes share one transaction.
                val local = converted.map { item -> item.copy(passkeys = item.passkeys.map { key ->
                    currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                    PasskeyPrivateKeyStore.protectPasskey(context, key).also {
                        check(PasskeyPrivateKeyStore.isProtectedReference(it.privateKeyAlias)) { "Private key could not be protected" }
                        check(PasskeyPrivateKeyStore.hasUsablePrivateKey(context, it.privateKeyAlias)) { "Protected private key cannot be read" }
                        protectedRefs += it.privateKeyAlias
                    }
                }) }
                currentCoroutineContext().ensureActive()
                db.withTransaction {
                    currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                    val duplicate = duplicatePasskeyProblems(document)
                    if (duplicate.isNotEmpty()) throw BitwardenJsonRejected(duplicate)
                    val folderIds = document.folders.associate { element ->
                        val folder = element.jsonObject
                        folder.string("id")!! to db.categoryDao().insert(Category(name = folder.string("name")!!))
                    }
                    local.forEachIndexed { index, item ->
                        currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                        val category = item.sourceFolder?.let { folderIds.getValue(it) }
                        val passwordId = item.password?.let {
                            db.passwordEntryDao().insertPasswordEntry(it.copy(categoryId = category)).also { id ->
                                check(id > 0)
                                db.customFieldDao().insertAll(item.fields.map { field -> field.copy(entryId = id) })
                                // DAO orders same-date history by descending row id: insert in reverse to retain order.
                                item.history.asReversed().forEach { history -> db.passwordHistoryDao().insert(history.copy(entryId = id)) }
                            }
                        }
                        item.secureItem?.let { check(db.secureItemDao().insertItem(it.copy(categoryId = category)) > 0) }
                        item.passkeys.forEach { key -> db.passkeyDao().insertCopy(key.copy(boundPasswordId = passwordId, categoryId = category)) }
                        progress.report(TransferProgress(TransferPhase.WRITING, index + 1L, local.size.toLong()))
                    }
                    currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                }
                committed = true
                ImportResultSummary(document.items.size, 0, 0, false)
            } finally {
                if (!committed && protectedRefs.isNotEmpty()) withContext(NonCancellable) {
                    // A reference can already be shared by another source; remove only proven unreferenced keys.
                    val used = db.passkeyDao().getAllPasskeysSync().map { it.privateKeyAlias }.toSet()
                    (protectedRefs - used).forEach { PasskeyPrivateKeyStore.removeIfProtectedReference(context, it) }
                }
            }
        }
    }

    suspend fun export(source: ImportDestination, uri: Uri, password: String?, permit: BitwardenJsonExportPermit,
        progress: TransferProgressReporter = TransferProgressReporter.None): BitwardenJsonExportSummary = withContext(Dispatchers.IO) {
        check(permit.consume(context, source)) { "Fresh export authentication required" }
        val epoch = epoch()
        suspend fun current() {
            requireCurrent(epoch)
            check(permit.isCurrent(context, source)) { "Source export authorization changed" }
        }
        progress.report(TransferProgress(TransferPhase.READING))
        val exported = when (source.kind) {
            ImportDestinationKind.LOCAL -> db.withTransaction {
                val snapshot = DatabaseExportSnapshotLoader(context).load(source, BackupPreferences(includeImages = false))
                val localCategories = db.categoryDao().getAllCategories().first().filter { it.bitwardenVaultId == null && it.mdbxDatabaseId == null }.associate { it.id to it.name }
                val legacyImageReferences = snapshot.secureItems.sumOf { item ->
                    if (item.imagePaths.isEmpty()) 0 else {
                        val images = Json.parseToJsonElement(security.decryptDataIfMonicaCiphertext(item.imagePaths)).jsonArray
                        require(images.all { it is JsonPrimitive && it.isString }) { "Unreadable legacy image references" }
                        images.size
                    }
                }
                val attachmentCount = legacyImageReferences + snapshot.passwords.map { it.id }.chunked(400).sumOf { db.attachmentDao().countExportMetadataByPasswords(it) } +
                    snapshot.secureItems.map { it.id }.chunked(400).sumOf { db.attachmentDao().countExportMetadataBySecureItems(it) }
                BitwardenJsonLocalExporter.export(snapshot.copy(categories = localCategories), db.passwordHistoryDao().getAllHistorySync(),
                    security::decryptDataIfMonicaCiphertext, { PasskeyPrivateKeyStore.resolve(context, it) }).copy(attachmentCount = attachmentCount)
            }
            ImportDestinationKind.BITWARDEN -> repository.exportPasswordManagerJson(source.databaseId)
            else -> throw BitwardenJsonRejected(listOf(BitwardenJsonIssue(null, null, "source", BitwardenJsonIssue.Reason.DESTINATION)))
        }
        current()
        progress.report(TransferProgress(if (password == null) TransferPhase.PREPARING else TransferPhase.ENCRYPTING))
        val bytes = BitwardenJsonCodec.encode(exported.document, password)
        try {
            current()
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                checkNotNull(output) { "Export destination unavailable" }
                for (offset in bytes.indices step 65536) {
                    currentCoroutineContext().ensureActive(); current()
                    val length = minOf(65536, bytes.size - offset)
                    output.write(bytes, offset, length)
                    progress.report(TransferProgress(TransferPhase.SAVING, (offset + length).toLong(), bytes.size.toLong(), bytes = true))
                }
                output.flush(); current()
            }
            BitwardenJsonExportSummary(exported.document.items.size, exported.document.folders.size, exported.attachmentCount)
        } catch (error: Throwable) {
            // The selected document was created for this export. Never leave a partial plaintext archive.
            withContext(NonCancellable) { runCatching { context.contentResolver.openOutputStream(uri, "wt")?.close() } }
            throw error
        } finally { bytes.fill(0) }
    }

    private suspend fun read(uri: Uri, epoch: Long): ByteArray {
        val buffer = ByteArray(16 * 1024)
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Import document unavailable" }
                val output = ByteArrayOutputStream()
                while (true) {
                    currentCoroutineContext().ensureActive(); requireCurrent(epoch)
                    val length = input.read(buffer)
                    if (length < 0) break
                    require(output.size().toLong() + length <= BitwardenJsonCodec.MAX_FILE_BYTES) { "Bitwarden JSON file exceeds 12 MiB" }
                    output.write(buffer, 0, length)
                }
                output.toByteArray()
            }
        } finally { buffer.fill(0) }
    }

    private suspend fun duplicatePasskeyProblems(document: BitwardenJsonDocument): List<BitwardenJsonIssue> {
        val existing = db.passkeyDao().getAllPasskeysSync().filter { it.bitwardenVaultId == null && it.keepassDatabaseId == null && it.mdbxDatabaseId == null }
            .map { it.rpId to PasskeyCredentialIdCodec.normalize(it.credentialId) }.toMutableSet()
        return buildList { document.items.forEachIndexed { index, element ->
            val item = element.jsonObject
            (item["login"] as? kotlinx.serialization.json.JsonObject)?.array("fido2Credentials")?.forEachIndexed { n, element ->
                val key = element.jsonObject
                if (!existing.add(key.string("rpId").orEmpty() to PasskeyCredentialIdCodec.normalize(key.string("credentialId").orEmpty()))) {
                    add(BitwardenJsonIssue(index, item.string("name")?.take(200), "items[$index].login.fido2Credentials[$n]", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE))
                }
            }
        } }
    }

    private companion object { val mutex = Mutex() }
}

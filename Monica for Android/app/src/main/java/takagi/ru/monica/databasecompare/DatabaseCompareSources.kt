package takagi.ru.monica.databasecompare

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.resolvedActiveStorageLocation
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.security.SecurityManager

internal sealed interface DatabaseCompareSource {
    val format: CompareFormat
    val label: String

    data class Registered(
        override val format: CompareFormat,
        val id: Long,
        override val label: String,
        val external: Boolean = false
    ) : DatabaseCompareSource

    data class Document(
        override val format: CompareFormat,
        val uri: Uri,
        override val label: String
    ) : DatabaseCompareSource
}

internal data class DatabaseCompareSelection(
    val source: DatabaseCompareSource,
    val password: CharArray,
    val keyFileUri: Uri? = null,
    val reuseAuthenticatedSession: Boolean = false
)

internal class PreparedCompareSource(
    val input: CompareInput,
    val sourceUris: Set<Uri>,
    val verifyUnchanged: suspend () -> Unit
) {
    fun clearCredentials() = input.clearCredentials()
}

/** Read-only encrypted capture. Registered cloud libraries use their existing offline file only. */
internal class DatabaseCompareSources(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val dao get() = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()

    private val service by lazy { KeePassKdbxService(this.context, dao, SecurityManager(this.context)) }

    suspend fun authenticatedIds(catalog: List<DatabaseCompareSource.Registered>): Set<Long> =
        catalog.mapNotNull { source ->
            if (service.comparisonCredentialReader(source.id) != null) source.id else null
        }.toSet()

    suspend fun catalog(format: CompareFormat): List<DatabaseCompareSource.Registered> =
        withContext(Dispatchers.IO) {
            require(format == CompareFormat.KDBX)
            dao.getAllDatabasesSync().map { DatabaseCompareSource.Registered(format, it.id, it.name,
                it.resolvedActiveStorageLocation() == KeePassStorageLocation.EXTERNAL) }
        }

    suspend fun document(uri: Uri): DatabaseCompareSource.Document = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "database.kdbx"
        DatabaseCompareSource.Document(CompareFormat.KDBX, uri, name)
    }

    suspend fun prepare(
        selection: DatabaseCompareSelection,
        directory: File,
        checkActive: () -> Unit
    ): PreparedCompareSource = try { withContext(Dispatchers.IO) {
        require(selection.source.format == CompareFormat.KDBX)
        val requestContext = currentCoroutineContext()
        fun checkRead() { requestContext.ensureActive(); checkActive() }
        checkRead()
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "input.kdbx")
        check(!file.exists())
        var keyBytes: ByteArray? = null
        var password: CharArray? = null
        try {
            val source = selection.source
            val registration = (source as? DatabaseCompareSource.Registered)?.let {
                checkNotNull(dao.getDatabaseById(it.id)) { "Comparison source is unavailable" }
            }
            val authenticated = if (selection.reuseAuthenticatedSession) {
                require(source is DatabaseCompareSource.Registered && selection.password.isEmpty() && selection.keyFileUri == null)
                checkNotNull(service.comparisonCredentialReader(source.id, checkActive)) { "KeePass authentication expired" }
            } else null
            val registrationBinding = registration?.let(::binding)
            val lockVersion = registration?.let { KeePassKdbxService.nativeLockVersions.value[it.id] ?: 0L }
            val sourceUri = when (source) {
                is DatabaseCompareSource.Document -> source.uri
                is DatabaseCompareSource.Registered -> uriFor(checkNotNull(registration))
            }
            val before = fingerprint(sourceUri, MAX_DATABASE_BYTES, ::checkRead)
            resolver.openInputStream(sourceUri)?.use { input ->
                file.outputStream().buffered().use { output ->
                    check(compareCopyAndHash(input, output, MAX_DATABASE_BYTES, ::checkRead) == before) {
                        "Comparison source changed while reading"
                    }
                }
            } ?: error("Comparison source is unavailable")
            check(file.length() > 0) { "Comparison source is empty" }
            check(fingerprint(sourceUri, MAX_DATABASE_BYTES, ::checkRead) == before) {
                "Comparison source changed while reading"
            }
            val keyUri = selection.keyFileUri
            val keyFingerprint = keyUri?.let { fingerprint(it, MAX_KEY_FILE_BYTES, ::checkRead) }
            keyBytes = keyUri?.let { uri ->
                resolver.openInputStream(uri)?.use { input ->
                    object : java.io.ByteArrayOutputStream() {
                        override fun close() { buf.fill(0); reset() }
                    }.use { bytes ->
                        check(compareCopyAndHash(input, bytes, MAX_KEY_FILE_BYTES, ::checkRead) == keyFingerprint) {
                            "Comparison key file changed while reading"
                        }
                        bytes.toByteArray()
                    }
                } ?: error("Comparison key file is unavailable")
            }
            require(keyBytes?.isNotEmpty() != false) { "Empty comparison key files are unsupported" }
            password = selection.password.copyOf()
            checkRead()
            PreparedCompareSource(
                CompareInput(file, source.label, checkNotNull(password), keyBytes, authenticatedCredentials = authenticated),
                setOfNotNull(sourceUri, keyUri),
                verifyUnchanged = {
                    val verificationContext = currentCoroutineContext()
                    fun checkVerification() { verificationContext.ensureActive(); checkActive() }
                    verificationContext.ensureActive()
                    checkActive()
                    if (source is DatabaseCompareSource.Registered) {
                        val current = checkNotNull(dao.getDatabaseById(source.id)) { "Comparison source was removed" }
                        check(binding(current) == registrationBinding && uriFor(current) == sourceUri) { "Comparison source changed" }
                        check((KeePassKdbxService.nativeLockVersions.value[source.id] ?: 0L) == lockVersion) {
                            "Comparison source was locked"
                        }
                    }
                    check(fingerprint(sourceUri, MAX_DATABASE_BYTES, ::checkVerification) == before) {
                        "Comparison source changed"
                    }
                    if (keyUri != null) check(fingerprint(keyUri, MAX_KEY_FILE_BYTES, ::checkVerification) == keyFingerprint) {
                        "Comparison key file changed"
                    }
                    checkActive()
                }
            ).also { it.verifyUnchanged() }
        } catch (failure: Throwable) {
            keyBytes?.fill(0)
            password?.fill('\u0000')
            file.delete()
            throw failure
        }
    } } finally { selection.password.fill('\u0000') }

    private fun uriFor(database: LocalKeePassDatabase): Uri {
        val path = database.resolvedActiveFilePath()
        if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.EXTERNAL) {
            val uri = Uri.parse(path)
            require(uri.scheme == "content" || uri.scheme == "file") { "Offline comparison source is unavailable" }
            return uri
        }
        val file = File(path).let { if (it.isAbsolute) it else File(context.filesDir, path) }
        check(file.isFile) { "Offline comparison source is unavailable" }
        return Uri.fromFile(file.canonicalFile)
    }

    private fun binding(database: LocalKeePassDatabase): List<Any?> = listOf(
        database.id, database.filePath, database.workingCopyPath, database.cacheCopyPath,
        database.sourceType, database.sourceId, database.storageLocation, database.openMode,
        database.keyFileUri, database.keyFileInternalPath, database.keyFileFingerprint, database.encryptedPassword
    )

    private fun fingerprint(uri: Uri, limit: Long, checkActive: () -> Unit): CompareFingerprint =
        resolver.openInputStream(uri)?.use { compareCopyAndHash(it, null, limit, checkActive) }
            ?: error("Comparison source is unavailable")

    companion object {
        const val MAX_DATABASE_BYTES = 128L * 1024 * 1024
        const val MAX_KEY_FILE_BYTES = 4L * 1024 * 1024
    }
}

internal data class CompareFingerprint(val size: Long, val sha256: String)

internal fun compareCopyAndHash(
    input: InputStream,
    output: OutputStream?,
    limit: Long,
    checkActive: () -> Unit = {}
): CompareFingerprint {
    require(limit >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var size = 0L
    try {
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0) { "Comparison source did not make progress" }
            check(size <= limit - count) { "Comparison file exceeds the supported size" }
            size += count
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        checkActive()
        return CompareFingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    } finally { buffer.fill(0) }
}

/** Document aliases can differ by tree URI but still identify the same document. */
internal fun comparisonUriIdentity(uri: Uri): String = when (uri.scheme) {
    "file" -> "file:${File(checkNotNull(uri.path)).canonicalPath}"
    "content" -> runCatching {
        "content:${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    }.getOrElse { uri.normalizeScheme().toString() }
    else -> uri.normalizeScheme().toString()
}

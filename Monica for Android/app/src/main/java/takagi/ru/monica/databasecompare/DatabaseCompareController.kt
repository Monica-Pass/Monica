package takagi.ru.monica.databasecompare

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

internal data class DatabaseCompareResult(val name: String, val hasSidecar: Boolean = false)

/**
 * One screen owns one controller. Invalidation revokes publication immediately, while native
 * close and encrypted temporary-file cleanup wait for the worker to exit. Native calls never
 * run on the main thread, and the process admits only one comparison operation at a time.
 */
internal class DatabaseCompareController(
    context: Context,
    private val sources: DatabaseCompareSources = DatabaseCompareSources(context)
) : Closeable {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val active = AtomicBoolean(true)
    private val lockGeneration = SessionManager.lockGeneration.value
    private val dataGeneration = SecurityManager.dataReadGeneration.value
    private val operation = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = File(this.context.cacheDir, "database-compare/${UUID.randomUUID()}")
    private var prepared: List<PreparedCompareSource> = emptyList()
    private var comparison: NativeDatabaseComparison? = null
    private var resultFile: File? = null
    private var resultFingerprint: CompareFingerprint? = null
    @Volatile var awaitingPicker: Boolean = false
        private set

    suspend fun compare(left: DatabaseCompareSelection, right: DatabaseCompareSelection): ComparePlan = try {
        work {
            require(left.source.format == CompareFormat.KDBX && right.source.format == CompareFormat.KDBX)
            clearWork()
            check(directory.mkdirs() || directory.isDirectory)
            try {
                val leftPrepared = sources.prepare(left, File(directory, "left"), ::checkActive)
                prepared = listOf(leftPrepared)
                val rightPrepared = sources.prepare(right, File(directory, "right"), ::checkActive)
                prepared = listOf(leftPrepared, rightPrepared)
                val operationContext = currentCoroutineContext()
                fun checkOperation() { operationContext.ensureActive(); checkActive() }
                val opened = KdbxDatabaseCompare.open(
                    leftPrepared.input, rightPrepared.input, File(directory, "native"), ::checkOperation
                )
                comparison = opened
                prepared.forEach { it.verifyUnchanged() }
                checkOperation()
                opened.plan
            } catch (failure: Throwable) {
                clearWork()
                throw failure
            }
        }
    } finally {
        left.password.fill('\u0000')
        right.password.fill('\u0000')
    }

    suspend fun buildResult(
        choices: Map<String, CompareChoice>,
        password: CharArray
    ): DatabaseCompareResult = try {
        work {
            val opened = checkNotNull(comparison) { "Comparison is no longer available" }
            opened.plan.validateChoices(choices)
            prepared.forEach { it.verifyUnchanged() }
            val output = File(directory, "result-${UUID.randomUUID()}.kdbx")
            val operationContext = currentCoroutineContext()
            fun checkOperation() { operationContext.ensureActive(); checkActive() }
            try {
                opened.writeResult(output, choices, password, ::checkOperation)
                checkOperation()
                prepared.forEach { it.verifyUnchanged() }
                val fingerprint = output.inputStream().use {
                    compareCopyAndHash(it, null, DatabaseCompareSources.MAX_DATABASE_BYTES, ::checkOperation)
                }
                check(fingerprint.size > 0L) { "Comparison result is empty" }
                // No plaintext/native sessions need survive the system destination picker.
                comparison = null
                opened.close()
                prepared.forEach { it.clearCredentials() }
                resultFile?.delete()
                resultFile = output
                resultFingerprint = fingerprint
                DatabaseCompareResult("Monica-merged.kdbx")
            } catch (failure: Throwable) {
                output.delete()
                throw failure
            }
        }
    } finally { password.fill('\u0000') }

    /** Call only after buildResult, immediately before launching CreateDocument. */
    fun suspendForPicker() {
        checkActive()
        check(resultFile != null && comparison == null) { "No encrypted result is ready" }
        awaitingPicker = true
    }

    /** Cancelling the picker also revokes the retained encrypted result. */
    fun cancelPicker() = invalidate()

    suspend fun exportDocument(destination: Uri) = work {
        awaitingPicker = false
        val source = checkNotNull(resultFile) { "Comparison result is unavailable" }
        val expected = checkNotNull(resultFingerprint)
        require(destination.scheme == "content") { "Select a new document destination" }
        val destinationIdentity = comparisonUriIdentity(destination)
        check(prepared.flatMap { it.sourceUris }.none { comparisonUriIdentity(it) == destinationIdentity }) {
            "Comparison cannot replace an input database or key file"
        }
        prepared.forEach { it.verifyUnchanged() }
        val operationContext = currentCoroutineContext()
        fun checkOperation() { operationContext.ensureActive(); checkActive() }
        check(source.inputStream().use {
            compareCopyAndHash(it, null, DatabaseCompareSources.MAX_DATABASE_BYTES, ::checkOperation)
        } == expected) { "Comparison result changed" }
        // CreateDocument may return an existing document on some providers. Never overwrite it.
        check(resolver.openInputStream(destination)?.use { it.read() == -1 } == true) {
            "Comparison destination must be a new empty document"
        }
        checkOperation()
        var writeStarted = false
        try {
            val descriptor = checkNotNull(resolver.openFileDescriptor(destination, "rw")) {
                "Comparison destination cannot be written"
            }
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                val channel = output.channel
                checkNotNull(channel.tryLock()) { "Comparison destination is in use" }.use {
                    // Opening in rw does not truncate. Check the actual open file again after
                    // acquiring its lock, closing the empty-check/open race for local providers.
                    check(channel.size() == 0L) { "Comparison destination must be empty" }
                    channel.position(0L)
                    writeStarted = true
                    source.inputStream().use { input ->
                        check(compareCopyAndHash(input, output, DatabaseCompareSources.MAX_DATABASE_BYTES, ::checkOperation) == expected)
                    }
                    output.flush()
                    channel.force(true)
                }
            }
            val actual = resolver.openInputStream(destination)?.use {
                compareCopyAndHash(it, null, DatabaseCompareSources.MAX_DATABASE_BYTES, ::checkOperation)
            } ?: error("Comparison destination cannot be verified")
            check(actual == expected) { "Comparison destination verification failed" }
            prepared.forEach { it.verifyUnchanged() }
            checkOperation()
            clearWork()
        } catch (failure: Throwable) {
            // The picker may have returned a pre-existing empty file. Restore that state,
            // rather than deleting a document whose creation cannot be proven by this API.
            // Unexpected bytes indicate another writer; in that case leave it untouched.
            if (writeStarted) runCatching {
                val descriptor = resolver.openFileDescriptor(destination, "rw") ?: return@runCatching
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val channel = output.channel
                    checkNotNull(channel.tryLock()).use {
                        channel.position(0L)
                        val matches = ParcelFileDescriptor.AutoCloseInputStream(
                            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                        ).use { actual ->
                            source.inputStream().use { expectedInput -> comparisonIsPrefix(actual, expectedInput) }
                        }
                        if (matches) { channel.truncate(0L); channel.force(true) }
                    }
                }
            }
            throw failure
        }
    }

    private fun checkActive() {
        val valid = active.get() && SessionManager.isUnlocked.value &&
            SessionManager.lockGeneration.value == lockGeneration &&
            SecurityManager.dataReadGeneration.value == dataGeneration
        if (!valid) {
            invalidate()
            error("Comparison session expired")
        }
    }

    private suspend fun <T> work(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        operation.withLock {
            checkActive()
            check(processOperation.tryLock()) { "Another database comparison is still running" }
            try {
                currentCoroutineContext().ensureActive()
                block().also { checkActive(); currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                invalidate()
                throw cancelled
            } finally {
                if (!active.get()) clearWork()
                processOperation.unlock()
            }
        }
    }

    fun invalidate() {
        if (!active.getAndSet(false)) return
        awaitingPicker = false
        cleanupScope.launch {
            operation.withLock { clearWork() }
        }
    }

    override fun close() = invalidate()

    private fun clearWork() {
        comparison?.let { runCatching { it.close() } }
        comparison = null
        prepared.forEach { it.clearCredentials() }
        prepared = emptyList()
        resultFile = null
        resultFingerprint = null
        awaitingPicker = false
        // Only this controller's unpredictable private directory is ever removed.
        val root = File(context.cacheDir, "database-compare").canonicalFile
        val own = directory.canonicalFile
        check(own.parentFile == root)
        if (own.exists()) own.deleteRecursively()
    }

    private companion object { val processOperation = Mutex() }
}

/** Used only to restore our own interrupted write into an initially empty destination. */
internal fun comparisonIsPrefix(actual: java.io.InputStream, expected: java.io.InputStream): Boolean {
    val buffer = ByteArray(32 * 1024)
    val expectedBuffer = ByteArray(buffer.size)
    var total = 0L
    try {
        while (true) {
            val count = actual.read(buffer)
            if (count < 0) return true
            if (count == 0) return false
            total += count
            if (total > DatabaseCompareSources.MAX_DATABASE_BYTES) return false
            var filled = 0
            while (filled < count) {
                val read = expected.read(expectedBuffer, filled, count - filled)
                if (read <= 0) return false
                filled += read
            }
            for (index in 0 until count) if (buffer[index] != expectedBuffer[index]) return false
        }
    } finally { buffer.fill(0); expectedBuffer.fill(0) }
}

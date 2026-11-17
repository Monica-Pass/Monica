package takagi.ru.monica.repository

import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxTigaScope
import uniffi.mdbx_ffi.MdbxTigaScopeType
import uniffi.mdbx_ffi.MdbxVault

/** Policy-bounded Rust handles only; list reads never prefetch or cache token plaintext. */
internal object Mdbx2NativeReadSessions {
    private val revocationListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    internal fun onRevoke(listener: () -> Unit): AutoCloseable {
        revocationListeners += listener
        return AutoCloseable { revocationListeners -= listener }
    }
    @Volatile
    var isForeground = false
        private set
    @Volatile var foregroundGeneration = 0L
        private set

    private data class FileStamp(val identity: String?, val size: Long, val modified: FileTime)
    private data class Key(
        val path: String,
        val file: FileStamp,
        val wal: FileStamp?,
        val syncedAt: Long?,
        val credential: String?,
        val unlockMethod: String,
        val keyFileUri: String?,
        val keyFileFingerprint: String?,
    )

    private val cacheHolder = lazy {
        Mdbx2ReadSessionCache<Key, MdbxVault>(
            CoroutineScope(SupervisorJob() + Dispatchers.IO), SystemClock::elapsedRealtime, MdbxVault::close)
    }
    private val cache by cacheHolder

    suspend fun <T> read(
        database: LocalMdbxDatabase,
        file: File,
        open: () -> MdbxVault,
        scope: MdbxTigaScope = MdbxTigaScope(MdbxTigaScopeType.VAULT, null),
        block: suspend (MdbxVault) -> T,
    ): T {
        fun stamp(target: File): FileStamp? = if (!target.isFile) null else {
            val attributes = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java)
            FileStamp(attributes.fileKey()?.toString(), attributes.size(), attributes.lastModifiedTime())
        }
        fun key() = Key(file.canonicalPath, checkNotNull(stamp(file)), stamp(File("${file.absolutePath}-wal")),
            // A local publication updates transport timestamps without replacing the working
            // file. Its identity/WAL revision and credentials still validate this connection.
            database.lastSyncedAt.takeIf { database.sourceTypeEnum !in setOf(
                takagi.ru.monica.data.MdbxSourceType.LOCAL_INTERNAL, takagi.ru.monica.data.MdbxSourceType.LOCAL_EXTERNAL) },
            database.encryptedPassword, database.unlockMethod,
            database.keyFileUri, database.keyFileFingerprint)

        val initial = key()
        return cache.use(database.id, initial, { isForeground && SessionManager.isUnlocked.value }, open,
            keyAfterRead = {
                // Native disclosure may append an audit record to the WAL. Track that revision,
                // but never associate an old connection with a replacement file at the same path.
                key().takeIf { it.path == initial.path && initial.file.identity != null &&
                    it.file.identity == initial.file.identity }
            },
            remainingLifetimeMillis = { vault ->
                // Recheck the requested resource, including stricter folder/entry policies.
                // End retention one second early because Rust reports whole seconds.
                (vault.readSessionRemainingSecs(scope).toLong() - 1L).coerceAtLeast(0L) * 1_000L
            },
            read = block)
    }

    fun invalidate(databaseId: Long) {
        if (cacheHolder.isInitialized()) cache.invalidate(databaseId)
    }

    fun updateForeground(foreground: Boolean) {
        isForeground = foreground
        if (!foreground) { foregroundGeneration++; clear() }
    }

    fun clear() {
        revocationListeners.forEach { it() }
        if (cacheHolder.isInitialized()) cache.clear()
    }
}

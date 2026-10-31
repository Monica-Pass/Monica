package takagi.ru.monica.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Owns plaintext intermediates, never the user's selected archive or a prepared export. */
internal object BackupTemporaryFiles {
    private const val PREFIX = "monica-sensitive-work-"
    private const val LEASE = ".lease"
    private const val LEGACY_MIN_AGE_MILLIS = 24L * 60 * 60 * 1000
    private val managedDirectory = Regex("^${PREFIX}[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val legacyDirectory = Regex("^(?:Monica_[0-9]{8}_[0-9]{6}_backup|restore_keepass_[0-9]+|zip-config-[A-Za-z0-9_-]+)$")
    private val legacyFile = Regex("^(?:Monica_[0-9]{8}_[0-9]{6}_generated_history\\.json|restore_decrypted_[0-9]+\\.zip|restore_[0-9]+_.+|import_temp_[0-9]+\\.zip)$")

    /** A dispatcher may discard a successful result if its caller is cancelled before resuming. */
    suspend fun <T : Any> deliverOnIo(discard: (T) -> Unit, block: suspend () -> T): T {
        var undelivered: T? = null
        try {
            val result = withContext(Dispatchers.IO) { block().also { undelivered = it } }
            undelivered = null
            return result
        } finally {
            undelivered?.let { result ->
                withContext(NonCancellable + Dispatchers.IO) { discard(result) }
            }
        }
    }

    class Workspace internal constructor(
        private val cache: Path,
        private val root: Path,
        private val channel: FileChannel,
        private val lease: FileLock,
    ) : Closeable {
        val directory: File = root.resolve("working").toFile()
        private var handedOff = false
        private var closed = false

        fun file(name: String): File {
            require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..")
            check(!closed)
            check(!Files.isSymbolicLink(directory.toPath())) { "Temporary backup directory is a link" }
            val path = directory.toPath().resolve(name)
            check(!Files.exists(path, NOFOLLOW_LINKS)) { "Temporary backup file already exists" }
            return path.toFile()
        }

        /** Called only once a parsed restore result is ready for its attachment consumer. */
        fun handOff(payloads: Map<String, File>): Pair<Map<String, File>, Closeable?> {
            check(!closed && !handedOff)
            if (payloads.isEmpty()) return payloads to null
            val destination = root.resolve("handoff")
            Files.createDirectory(destination)
            val moved = mutableMapOf<File, File>()
            val result = payloads.mapValues { (_, file) ->
                moved.getOrPut(file) {
                    val source = file.toPath().toAbsolutePath().normalize()
                    require(source.parent == directory.toPath()) { "Unexpected attachment staging location" }
                    require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Unexpected attachment staging link" }
                    val target = destination.resolve(UUID.randomUUID().toString())
                    Files.move(source, target).toFile()
                }
            }
            handedOff = true
            return result to Closeable { synchronized(BackupTemporaryFiles) { finish() } }
        }

        override fun close() = synchronized(BackupTemporaryFiles) {
            if (closed) return@synchronized
            if (handedOff) {
                try { deleteContained(cache, directory.toPath()) }
                catch (error: Throwable) { finish(); throw error }
            } else finish()
        }

        /** A restore result that was not delivered must not retain attachment plaintext. */
        fun discard() = synchronized(BackupTemporaryFiles) { finish() }

        private fun finish() {
            if (closed) return
            withCacheLock(cache) {
                closed = true
                try { lease.release() } finally { channel.close() }
                deleteContained(cache, root)
            }
        }
    }

    @Synchronized
    fun create(cacheDir: File): Workspace {
        val cache = checkedCache(cacheDir)
        return withCacheLock(cache) {
            val root = Files.createDirectory(cache.resolve(PREFIX + UUID.randomUUID()))
            var channel: FileChannel? = null
            var lease: FileLock? = null
            try {
                channel = FileChannel.open(root.resolve(LEASE), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                lease = channel.lock()
                Files.createDirectory(root.resolve("working"))
                Workspace(cache, root, channel, lease)
            } catch (error: Throwable) {
                try { lease?.release() } finally { channel?.close() }
                deleteContained(cache, root)
                throw error
            }
        }
    }

    /** Locks survive across app processes and are released by the OS after a crash. */
    @Synchronized
    fun cleanOrphans(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        val cache = checkedCache(cacheDir)
        return withCacheLock(cache) { cleanOrphansLocked(cache, now) }
    }

    private fun cleanOrphansLocked(cache: Path, now: Long): Int {
        var deleted = 0
        var failed = false
        Files.newDirectoryStream(cache).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val managed = managedDirectory.matches(name)
                val directory = Files.isDirectory(entry, NOFOLLOW_LINKS)
                val legacy = if (directory || Files.isSymbolicLink(entry)) legacyDirectory.matches(name) else legacyFile.matches(name)
                if (!managed && !legacy) continue
                if (!managed && now - Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toMillis() < LEGACY_MIN_AGE_MILLIS) continue
                try {
                    if (managed && directory && isLeased(entry)) continue
                    deleteContained(cache, entry)
                    deleted++
                } catch (_: IOException) {
                    failed = true
                }
            }
        }
        if (failed) throw IOException("Some temporary backup files could not be removed")
        return deleted
    }

    private fun isLeased(root: Path): Boolean {
        val path = root.resolve(LEASE)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
        FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { return true }
            if (lock == null) return true
            lock.release()
        }
        return false
    }

    private fun <T> withCacheLock(cache: Path, block: () -> T): T =
        FileChannel.open(cache.resolve(".monica-sensitive-cleanup.lock"), CREATE, READ, WRITE, NOFOLLOW_LINKS)
            .use { channel -> channel.lock().use { block() } }

    private fun checkedCache(directory: File): Path {
        require(!Files.isSymbolicLink(directory.toPath())) { "Unexpected cache directory link" }
        val cache = directory.canonicalFile.toPath()
        require(Files.isDirectory(cache, NOFOLLOW_LINKS)) { "Cache directory unavailable" }
        return cache
    }

    /** NOFOLLOW_LINKS is intentional: remove a link itself, never anything at its target. */
    private fun deleteContained(cache: Path, target: Path) {
        val path = target.toAbsolutePath().normalize()
        require(path != cache && path.startsWith(cache)) { "Temporary backup path outside cache" }
        var parent = path.parent
        while (parent != cache) {
            require(parent != null && !Files.isSymbolicLink(parent)) { "Temporary backup parent is a link" }
            parent = parent.parent
        }
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

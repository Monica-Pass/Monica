package takagi.ru.monica.lansync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference

/** Leases cover preparation and all native workers, including retired workers. */
internal object DeviceSyncCacheRoots {
    private const val OWNER = ".owner.lock"
    private const val GATE = ".roots.lock"
    private const val MAX_NODES = 4096
    private const val MAX_ROOT_ENTRIES = 4096
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val delta = Regex("\\.delta-[0-9a-f]{64}|\\.delta-staging-[A-Za-z0-9_-]{22}")
    private val partial = Regex("[0-9a-f]{64}-[0-9a-f]{64}\\.part")
    private val replicaTemporary = Regex("\\.replica-[A-Za-z0-9_-]{22}\\.tmp")
    private val artifacts = setOf("vault.mdbx", "changes.mdbxsync", "assets.monica")
    private val processGate = Mutex()
    // Do not even open another descriptor for a locally leased owner inode:
    // closing it can release POSIX process-wide fcntl locks held by the first.
    private val leases = mutableMapOf<Path, Lease>()

    internal class Lease internal constructor(
        internal val path: Path,
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : Closeable {
        val root: File get() = path.toFile()
        override fun close() {
            synchronized(leases) {
                if (leases[path] !== this) return
                try { lock.release() } finally {
                    try { channel.close() } finally { leases.remove(path) }
                }
            }
        }
    }

    /** No filesystem work runs in the constructor/caller's main dispatcher. */
    suspend fun acquire(cacheDir: File, rootName: String): Lease {
        if (!uuid.matches(rootName)) throw failure()
        val published = AtomicReference<Lease?>()
        try {
            return withContext(Dispatchers.IO) {
                val cache = cacheDir.toPath().toAbsolutePath().normalize()
                directory(cache)
                val base = cache.toRealPath().resolve("device-sync")
                if (attributes(base) == null) {
                    try { Files.createDirectory(base) } catch (_: java.nio.file.FileAlreadyExistsException) { }
                }
                directory(base)
                val candidates = Files.newDirectoryStream(base).use { entries ->
                    val candidates = mutableListOf<Path>()
                    var count = 0
                    for (entry in entries) {
                        if (++count > MAX_ROOT_ENTRIES) throw failure()
                        if (uuid.matches(entry.fileName.toString()) && entry.fileName.toString() != rootName) candidates.add(entry)
                    }
                    candidates
                }
                for (candidate in candidates) {
                    currentCoroutineContext().ensureActive()
                    reclaim(base, candidate)
                }
                currentCoroutineContext().ensureActive()
                val root = base.resolve(rootName)
                if (attributes(root) != null && attributes(root.resolve(OWNER)) == null && layout(root) == null) throw failure()
                val lease = withGate(base) {
                    if (attributes(root) == null) Files.createDirectory(root)
                    directory(root)
                    tryLease(root) ?: throw failure()
                }
                published.set(lease)
                // An existing requested root is adoptable only in this exact
                // protocol namespace; unknown files must not become sync state.
                if (layout(root) == null) throw failure()
                currentCoroutineContext().ensureActive()
                lease
            }
        } catch (failure: Throwable) {
            // withContext may cancel at its return boundary after a lease exists.
            // No caller may write until the Lease was received successfully.
            withContext(NonCancellable + Dispatchers.IO) { published.getAndSet(null)?.close() }
            throw failure
        }
    }

    private suspend fun reclaim(base: Path, root: Path) {
        val owner = root.resolve(OWNER)
        // An unmarked legacy root must first prove the bounded protocol layout.
        // This preflight is outside the global gate and never deletes anything.
        if (attributes(root) == null) return
        directory(root)
        if (attributes(owner) == null && layout(root) == null) return
        val lease = withGate(base) {
            if (attributes(root) == null) null else {
                directory(root)
                tryLease(root)
            }
        } ?: return
        try {
            val plan = layout(root) ?: return
            for (path in plan) {
                currentCoroutineContext().ensureActive()
                noLinkedParents(root, path.parent)
                val metadata = attributes(path) ?: continue
                if (metadata.isSymbolicLink || (!metadata.isRegularFile && !metadata.isDirectory)) throw failure()
                Files.delete(path)
            }
            // Keep the owner inode leased during all recursive work. Closing and
            // removing it plus the empty root is atomic with respect to creators
            // and scanners, under only a short global gate.
            withGate(base) {
                directory(root)
                regular(owner)
                lease.close()
                Files.delete(owner)
                Files.delete(root)
            }
        } finally { lease.close() }
    }

    private suspend fun <T> withGate(base: Path, block: () -> T): T = processGate.withLock {
        directory(base)
        val path = base.resolve(GATE)
        val deadline = System.nanoTime() + 5_000_000_000L
        openRegular(path).use { channel ->
            var locked: FileLock? = null
            try {
                while (locked == null) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) throw failure()
                    locked = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (locked == null) delay(10)
                }
                currentCoroutineContext().ensureActive()
                block()
            } finally { locked?.release() }
        }
    }

    /** Called while the global gate excludes root creation and owner removal. */
    private fun tryLease(root: Path): Lease? = synchronized(leases) {
        if (leases.containsKey(root)) return@synchronized null
        val owner = root.resolve(OWNER)
        if (attributes(owner) != null && Files.isSameFile(owner,root.parent.resolve(GATE))) throw failure()
        val channel = openRegular(owner)
        try {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) { channel.close(); null }
            else Lease(root, channel, lock).also { leases[root] = it }
        } catch (failure: Throwable) { channel.close(); throw failure }
    }

    private fun openRegular(path: Path): FileChannel {
        attributes(path)?.let {
            if (!it.isRegularFile || it.isSymbolicLink || it.size() != 0L) throw failure()
            synchronized(leases) {
                if (leases.values.any { lease -> Files.isSameFile(path,lease.path.resolve(OWNER)) }) throw failure()
            }
        }
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
    }

    /** Returns postorder paths, excluding the leased owner and root themselves. */
    private fun layout(root: Path): List<Path>? {
        directory(root)
        val plan = mutableListOf<Path>()
        var nodes = 0
        fun visit(directory: Path, kind: String): Boolean {
            Files.newDirectoryStream(directory).use { entries ->
                for (path in entries) {
                    if (++nodes > MAX_NODES) return false
                    val name = path.fileName.toString()
                    val metadata = attributes(path) ?: return false
                    if (name == OWNER && kind == "root") {
                        if (!metadata.isRegularFile || metadata.isSymbolicLink || metadata.size() != 0L) throw failure()
                        continue
                    }
                    if (metadata.isSymbolicLink) return false
                    val childKind = when {
                        kind == "root" && name == "resume" -> "resume"
                        kind == "root" && name.startsWith("out-") && uuid.matches(name.removePrefix("out-")) -> "out"
                        kind == "root" && name.startsWith("in-") && uuid.matches(name.removePrefix("in-")) -> "in"
                        kind == "out" && delta.matches(name) -> "delta"
                        kind == "in" && name.startsWith("local-assets-") && uuid.matches(name.removePrefix("local-assets-")) -> "local-assets"
                        else -> null
                    }
                    if (metadata.isDirectory) {
                        if (childKind == null || !visit(path, childKind)) return false
                    } else if (metadata.isRegularFile) {
                        val recognized = when (kind) {
                            "out" -> name in artifacts || name == ".pairing.replica" || replicaTemporary.matches(name)
                            "in" -> name in artifacts || name.removeSuffix(".part") in artifacts && name.endsWith(".part")
                            "delta" -> name in artifacts || replicaTemporary.matches(name)
                            "local-assets" -> name == "assets.monica"
                            "resume" -> partial.matches(name)
                            else -> false
                        }
                        if (!recognized) return false
                    } else return false
                    plan.add(path)
                }
            }
            return true
        }
        return if (visit(root, "root")) plan else null
    }

    private fun noLinkedParents(root: Path, parent: Path) {
        if (!parent.startsWith(root)) throw failure()
        var path = parent
        while (true) {
            directory(path)
            if (path == root) return
            path = path.parent ?: throw failure()
        }
    }

    private fun directory(path: Path) {
        val metadata = attributes(path) ?: throw failure()
        if (!metadata.isDirectory || metadata.isSymbolicLink) throw failure()
    }
    private fun regular(path: Path) {
        val metadata = attributes(path) ?: throw failure()
        if (!metadata.isRegularFile || metadata.isSymbolicLink) throw failure()
    }
    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
    private fun failure() = IOException("device_sync_cache_root")
}

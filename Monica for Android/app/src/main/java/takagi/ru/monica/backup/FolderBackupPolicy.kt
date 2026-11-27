package takagi.ru.monica.backup

import java.io.InputStream
import java.security.MessageDigest

internal data class FolderBackupRecord(
    val uri: String,
    val tree: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val keyId: String,
    val permanent: Boolean,
    val complete: Boolean = false,
)

/** Only the installation's durable ledger grants cleanup ownership, never a filename pattern. */
internal object FolderBackupPolicy {
    const val CLOUD_GRACE_MS = 48 * 60 * 60 * 1000L
    const val QUIET_MS = 2 * 60 * 1000L
    const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    const val PERIOD_MS = 12 * 60 * 60 * 1000L

    fun delayMillis(now: Long, changedAt: Long, lastBackup: Long): Long =
        maxOf(0L, changedAt + QUIET_MS - now, lastBackup + MIN_INTERVAL_MS - now)

    fun cleanupCandidates(records: List<FolderBackupRecord>, tree: String, keyId: String,
        keep: Int, now: Long, local: Boolean): List<FolderBackupRecord> {
        require(keep in 1..1000)
        val eligible = records.filter { it.complete && !it.permanent && it.tree == tree && it.keyId == keyId }
            .sortedWith(compareByDescending<FolderBackupRecord> { it.createdAt }.thenBy { it.uri })
        if (local) return eligible.drop(keep)
        // Providers such as RSAF may acknowledge close/readback before their remote upload finishes.
        // Keep all recent files plus at least two older generations; never infer remote durability.
        return eligible.filter { it.createdAt > 0 && now - it.createdAt >= CLOUD_GRACE_MS }
            .drop(maxOf(keep, 2))
    }

    fun digest(input: InputStream, expectedSize: Long, check: () -> Unit = {}): String {
        require(expectedSize >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            check()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) throw java.io.IOException("Provider made no read progress")
            count += read
            if (count > expectedSize) throw java.io.IOException("Backup size changed")
            digest.update(buffer, 0, read)
        }
        check()
        if (count != expectedSize) throw java.io.IOException("Backup is truncated")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

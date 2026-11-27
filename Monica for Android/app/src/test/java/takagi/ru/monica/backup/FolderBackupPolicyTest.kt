package takagi.ru.monica.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class FolderBackupPolicyTest {
    private val now = 10 * FolderBackupPolicy.CLOUD_GRACE_MS
    private fun record(id: Int, age: Long = id * 1000L, tree: String = "tree", key: String = "key",
        permanent: Boolean = false, complete: Boolean = true) = FolderBackupRecord("uri$id", tree,
        "monica_backup_$id.enc.zip", 3, "digest", now - age, key, permanent, complete)

    @Test fun localRotationNeverIncludesOtherFoldersPasswordsManualOrIncompleteFiles() {
        val files = (1..6).map { record(it) } + listOf(record(7, tree = "other"), record(8, key = "old-key"),
            record(9, permanent = true), record(10, complete = false))
        assertEquals(listOf("uri3", "uri4", "uri5", "uri6"),
            FolderBackupPolicy.cleanupCandidates(files, "tree", "key", 2, now, true).map { it.uri })
    }

    @Test fun cloudRotationKeepsAllRecentAndTwoOlderGenerationsEvenWithLimitOne() {
        val recent = (1..4).map { record(it) }
        val older = (5..9).map { record(it, (it - 3) * FolderBackupPolicy.CLOUD_GRACE_MS) }
        assertEquals(listOf("uri7", "uri8", "uri9"), FolderBackupPolicy.cleanupCandidates(
            recent + older, "tree", "key", 1, now, false).map { it.uri })
        assertTrue(FolderBackupPolicy.cleanupCandidates(recent, "tree", "key", 1, now, false).isEmpty())
    }

    @Test fun clockRollbackAndUnknownTimestampsAreNeverCloudCleanupCandidates() {
        assertTrue(FolderBackupPolicy.cleanupCandidates(listOf(record(1, -100), record(2, now)),
            "tree", "key", 1, now, false).isEmpty())
    }

    @Test fun quietPeriodAndMinimumIntervalAreBothEnforced() {
        assertEquals(FolderBackupPolicy.QUIET_MS, FolderBackupPolicy.delayMillis(now, now, 0))
        assertEquals(FolderBackupPolicy.MIN_INTERVAL_MS, FolderBackupPolicy.delayMillis(now, 0, now))
        assertEquals(0, FolderBackupPolicy.delayMillis(now, 0, 0))
    }

    @Test fun digestRejectsTruncatedAndOversizedReadback() {
        for (size in listOf(2L, 4L)) {
            try { FolderBackupPolicy.digest(ByteArrayInputStream(byteArrayOf(1, 2, 3)), size); fail("Accepted wrong size") }
            catch (_: IOException) { }
        }
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            FolderBackupPolicy.digest(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
    }

    @Test fun revokedSessionStopsReadbackBeforeAcceptingDigest() {
        var checks = 0
        try {
            FolderBackupPolicy.digest(ByteArrayInputStream(ByteArray(200_000)), 200_000) {
                if (++checks == 2) throw IllegalStateException("Locked")
            }
            fail("Accepted revoked readback")
        } catch (error: IllegalStateException) { assertEquals("Locked", error.message) }
        assertEquals(2, checks)
    }
}

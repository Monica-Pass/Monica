package takagi.ru.monica.data

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotIntegrationGuardTest {

    @Test
    fun roomDatabaseRegistersVersion73SnapshotMigration() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt"
        ).readSourceText()

        assertTrue(source.contains("TimelineVersionSnapshot::class"))
        assertTrue(Regex("version = (\\d+)").find(source)!!.groupValues[1].toInt() >= 73)
        assertTrue(source.contains("Migration(72, 73)"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS timeline_version_snapshots"))
        assertTrue(source.contains("MIGRATION_72_73"))
    }

    @Test
    fun snapshotPayloadNeverUsesCompatibilityFallbackWhenMasterPasswordExists() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()

        val encryptBlock = source.substringAfter("fun encryptTimelineSnapshot")
            .substringBefore("fun decryptTimelineSnapshot")
        assertTrue(encryptBlock.contains("isVaultRuntimeUnlocked()"))
        assertTrue(encryptBlock.contains("DATA_PREFIX_MDK"))
        assertFalse(encryptBlock.contains("encryptDataCompat(data)") &&
            !encryptBlock.contains("if (!isMasterPasswordSet())"))

        val decryptBlock = source.substringAfter("fun decryptTimelineSnapshot")
            .substringBefore("fun looksLikeMonicaCiphertext")
        assertTrue(decryptBlock.contains("isVaultRuntimeUnlocked()"))
        assertTrue(decryptBlock.contains("DATA_PREFIX_MDK"))
        assertTrue(decryptBlock.contains("DATA_PREFIX_COMPAT"))
    }

    @Test
    fun ordinaryTimelineBackupDoesNotExportEncryptedSnapshots() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readSourceText()
        val timelineBackupBlock = source.substringAfter("val allLogs = operationLogDao.getAllLogsSync()")
            .substringBefore("OperationLogger.logWebDavUpload")

        assertTrue(timelineBackupBlock.contains("OperationLogBackupEntry"))
        assertFalse(timelineBackupBlock.contains("timelineVersionSnapshotDao"))
        assertFalse(timelineBackupBlock.contains("encryptedChangesJson"))
    }

    @Test
    fun loggerUsesJournalAndRecoveryMaterialsRemainEncrypted() {
        val logger = projectFile("app/src/main/java/takagi/ru/monica/utils/OperationLogger.kt").readSourceText()
        val journal = projectFile("app/src/main/java/takagi/ru/monica/timeline/TimelineJournal.kt").readSourceText()
        assertTrue(logger.contains("db.timelineJournal.observe("))
        assertTrue(logger.contains("snapshotChanges: List<FieldChange> = changes"))
        assertFalse(logger.contains("operationLogDao().insert"))
        assertTrue(journal.contains("security.encryptTimelineSnapshot"))
        assertTrue(journal.contains("sealedDetails"))
    }

    @Test
    fun restoreAndCopyRefuseStaleOrKeePassBoundVersions() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TimelineViewModel.kt"
        ).readSourceText()

        assertTrue(source.contains("if (current != expectedValue(change)) return false"))
        assertTrue(source.contains("matchesCurrentEncryptedSnapshotState(log, snapshotChanges)"))
        assertTrue(source.contains("if (entry.keepassDatabaseId != null) return false"))
        assertTrue(source.contains("if (item.keepassDatabaseId != null) return false"))
        assertTrue(source.contains("bitwardenVaultId = null"))
        assertTrue(source.contains("mdbxDatabaseId = null"))
        assertTrue(source.contains("replicaGroupId = null"))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}

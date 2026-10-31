package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

class BackupRetentionPolicyTest {
    @Test fun generatedArchiveNamesEnterRetentionButMarkedAndUnrelatedFilesRemainOutsideIt() {
        val cache = Files.createTempDirectory("backup-retention-name-test-").toFile()
        try {
            val plain = File.createTempFile("monica_backup_20260930_120000_", ".zip", cache)
            val encrypted = File.createTempFile("monica_backup_20260930_120000_", ".enc.zip", cache)
            val newest = File.createTempFile("monica_backup_20261031_120000_", ".enc.zip", cache)
            listOf(plain, encrypted, newest).forEach {
                assertTrue("Generated archive must remain managed: ${it.name}", BackupRetentionPolicy.isManagedTemporaryBackup(it.name))
            }
            val excluded = listOf(
                plain.name.replace(".zip", "_permanent.zip"),
                encrypted.name.replace(".zip", "_permanent.zip"),
                encrypted.name.replace(".enc.zip", "_partial_fixture.enc.zip"),
                "family_12345.zip", "monica_backup_20260930_120000_custom.zip",
                "monica_backup_20260930_120000_123_extra.zip",
                "monica_backup_20260930_120000_123456789012345678901.zip",
            )
            excluded.forEach { assertFalse(it, BackupRetentionPolicy.isManagedTemporaryBackup(it)) }
            val candidates = listOf(backup(plain.name, 1000L), backup(encrypted.name, 2000L), backup(newest.name, 3000L)) +
                excluded.map { backup(it, 1L) }
            val managed = candidates.filter { BackupRetentionPolicy.isManagedTemporaryBackup(it.name) }
            assertEquals(listOf(plain.name, encrypted.name), BackupRetentionPolicy.backupsToDelete(
                managed, BackupRetentionConfig(enabled = true, maxBackups = 1), protectedBackupName = newest.name,
            ).map { it.name })
        } finally { check(cache.deleteRecursively()) }
    }

    @Test fun automaticCleanupOnlyOwnsStandardTemporaryArchiveNames() {
        assertTrue(BackupRetentionPolicy.isManagedTemporaryBackup("monica_backup_20260930_120000.zip"))
        assertTrue(BackupRetentionPolicy.isManagedTemporaryBackup("monica_backup_20260930_120000.enc.zip"))
        listOf("family.zip", "monica_backup_custom.zip", "monica_backup_20260930_120000_permanent.zip",
            "monica_backup_20260930_120000.enc_permanent.zip", "../monica_backup_20260930_120000.zip",
            "monica_backup_20260930_120000_partial_fixture_permanent.enc.zip").forEach {
            assertFalse(it, BackupRetentionPolicy.isManagedTemporaryBackup(it))
        }
    }

    @Test
    fun cleanupKeepsNewestTemporaryBackupsEvenWhenAllAreOlderThanRetentionWindow() {
        val now = 1_800_000_000_000L
        val oldBackups = (1..12).map { index ->
            backup(
                name = "backup_$index.zip",
                modifiedMillis = now - (60L + index) * DAY_MILLIS
            )
        }

        val toDelete = BackupRetentionPolicy.expiredTemporaryBackupsToDelete(
            backups = oldBackups,
            nowMillis = now,
            minTemporaryBackupsToKeep = 10
        )

        assertEquals(listOf("backup_11.zip", "backup_12.zip"), toDelete.map { it.name })
    }

    @Test
    fun cleanupNeverDeletesPermanentOrUnknownTimestampBackups() {
        val now = 1_800_000_000_000L
        val permanent = backup("old_permanent.zip", now - 120L * DAY_MILLIS)
        val unknownTimestamp = backup("unknown.zip", 0L)
        val oldTemporary = backup("old.zip", now - 120L * DAY_MILLIS)

        val toDelete = BackupRetentionPolicy.expiredTemporaryBackupsToDelete(
            backups = listOf(permanent, unknownTimestamp, oldTemporary),
            nowMillis = now,
            minTemporaryBackupsToKeep = 0
        )

        assertEquals(listOf("old.zip"), toDelete.map { it.name })
        assertFalse(toDelete.any { it.name == permanent.name || it.name == unknownTimestamp.name })
    }

    @Test
    fun disabledCountLimitPreservesExistingAgePolicy() {
        val now = 1_800_000_000_000L
        val backups = (1..12).map { backup("backup_$it.zip", now - (60L + it) * DAY_MILLIS) }

        val toDelete = BackupRetentionPolicy.backupsToDelete(
            backups, BackupRetentionConfig(enabled = false, maxBackups = 1), nowMillis = now
        )

        assertEquals(listOf("backup_11.zip", "backup_12.zip"), toDelete.map { it.name })
    }

    @Test
    fun countLimitDeletesOldestRecentBackupsFirst() {
        val backups = listOf(
            backup("newest.enc.zip", 4000L),
            backup("oldest.zip", 1000L),
            backup("second-newest.zip", 3000L),
            backup("second-oldest.enc.zip", 2000L)
        )

        val toDelete = BackupRetentionPolicy.backupsToDelete(
            backups, BackupRetentionConfig(enabled = true, maxBackups = 2), nowMillis = 5000L
        )

        assertEquals(listOf("oldest.zip", "second-oldest.enc.zip"), toDelete.map { it.name })
    }

    @Test
    fun countAtOrBelowLimitDoesNotDeleteEvenWhenBackupsAreVeryOld() {
        val now = 1_800_000_000_000L
        for (count in listOf(0, 1, 14, 15)) {
            val backups = (1..count).map { backup("backup_$it.zip", now - (90L + it) * DAY_MILLIS) }
            assertTrue(BackupRetentionPolicy.backupsToDelete(
                backups, BackupRetentionConfig(enabled = true, maxBackups = 15), nowMillis = now
            ).isEmpty())
        }
    }

    @Test
    fun permanentBackupsAreNeverDeletedAndDoNotConsumeSlots() {
        val backups = listOf(
            backup("old_permanent.zip", 100L),
            backup("encrypted_permanent.enc.zip", 200L),
            backup("encrypted.enc_permanent.zip", 5000L),
            backup("old.zip", 1000L),
            backup("keep.enc.zip", 2000L),
            backup("newest.zip", 3000L)
        )

        assertEquals(listOf("old.zip"), BackupRetentionPolicy.backupsToDelete(
            backups, BackupRetentionConfig(enabled = true, maxBackups = 2)
        ).map { it.name })
        assertTrue(BackupRetentionPolicy.backupsToDelete(
            backups.filter(BackupFile::isPermanent),
            BackupRetentionConfig(enabled = true, maxBackups = 1)
        ).isEmpty())
    }

    @Test
    fun removingPermanentMarkMakesOldBackupEligibleAgain() {
        val old = backup("old_permanent.zip", 1000L)
        val newest = backup("newest.zip", 2000L)
        val config = BackupRetentionConfig(enabled = true, maxBackups = 1)

        assertTrue(BackupRetentionPolicy.backupsToDelete(listOf(old, newest), config).isEmpty())
        assertEquals(listOf("old.zip"), BackupRetentionPolicy.backupsToDelete(
            listOf(old.copy(name = "old.zip"), newest), config
        ).map { it.name })
    }

    @Test
    fun unknownTimestampsStayOutsideTheLimit() {
        val backups = listOf(
            backup("unknown.zip", 0L),
            backup("invalid.zip", -1L),
            backup("old.zip", 1000L),
            backup("newest.zip", 2000L)
        )

        assertEquals(listOf("old.zip"), BackupRetentionPolicy.backupsToDelete(
            backups, BackupRetentionConfig(enabled = true, maxBackups = 1)
        ).map { it.name })
        assertFalse(backups[0].isExpiring)
        assertFalse(backups[1].isExpiring)
    }

    @Test
    fun equalTimestampsHaveDeterministicRetentionRegardlessOfListingOrder() {
        val backups = listOf("a.zip", "c.zip", "b.zip").map { backup(it, 1000L) }
        val config = BackupRetentionConfig(enabled = true, maxBackups = 1)

        assertEquals(listOf("a.zip", "b.zip"),
            BackupRetentionPolicy.backupsToDelete(backups, config).map { it.name })
        assertEquals(listOf("a.zip", "b.zip"),
            BackupRetentionPolicy.backupsToDelete(backups.reversed(), config).map { it.name })
    }

    @Test
    fun duplicateResourcesDoNotConsumeExtraSlotsOrDeleteTheNewestFile() {
        val backups = listOf(
            backup("newest.zip", 1000L),
            backup("newest.zip", 3000L),
            backup("old.zip", 2000L)
        )

        assertEquals(listOf("old.zip"), BackupRetentionPolicy.backupsToDelete(
            backups, BackupRetentionConfig(enabled = true, maxBackups = 1)
        ).map { it.name })
    }

    @Test
    fun newlyUploadedBackupIsProtectedEvenWithMissingOrIncorrectServerTime() {
        for (uploadedTime in listOf(0L, 100L)) {
            val backups = listOf(backup("uploaded.zip", uploadedTime), backup("previous.zip", 1000L))
            assertEquals(listOf("previous.zip"), BackupRetentionPolicy.backupsToDelete(
                backups,
                BackupRetentionConfig(enabled = true, maxBackups = 1),
                protectedBackupName = "uploaded.zip"
            ).map { it.name })
        }
    }

    @Test
    fun invalidCountCannotDeleteEveryRegularBackup() {
        val backups = listOf(backup("old.zip", 1000L), backup("newest.zip", 2000L))
        for (invalidCount in listOf(Int.MIN_VALUE, -1, 0)) {
            assertEquals(listOf("old.zip"), BackupRetentionPolicy.backupsToDelete(
                backups, BackupRetentionConfig(enabled = true, maxBackups = invalidCount)
            ).map { it.name })
        }
    }

    private fun backup(name: String, modifiedMillis: Long): BackupFile {
        return BackupFile(
            name = name,
            path = "/$name",
            size = 1024L,
            modified = Date(modifiedMillis)
        )
    }

    private companion object {
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}

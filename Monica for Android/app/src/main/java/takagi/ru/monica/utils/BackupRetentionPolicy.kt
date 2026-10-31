package takagi.ru.monica.utils

import kotlinx.serialization.Serializable

@Serializable
data class BackupRetentionConfig(
    val enabled: Boolean = false,
    val maxBackups: Int = DEFAULT_MAX_BACKUPS
) {
    companion object {
        const val DEFAULT_MAX_BACKUPS = 10
        val MAX_BACKUPS_RANGE = 1..1000
    }
}

object BackupRetentionPolicy {
    // A shared/root directory can contain unrelated ZIPs. Unknown or renamed
    // files remain downloadable but must never enter automatic deletion.
    // File.createTempFile adds a numeric suffix to isolate concurrently prepared backups.
    private val managedTemporaryName = Regex("^monica_backup_[0-9]{8}_[0-9]{6}(?:_[0-9]{1,20})?(?:\\.enc)?\\.zip$")

    fun isManagedTemporaryBackup(name: String): Boolean = managedTemporaryName.matches(name)

    const val DEFAULT_RETENTION_DAYS: Long = 60L
    const val DEFAULT_MIN_TEMPORARY_BACKUPS_TO_KEEP: Int = 10

    fun backupsToDelete(
        backups: List<BackupFile>,
        config: BackupRetentionConfig,
        nowMillis: Long = System.currentTimeMillis(),
        protectedBackupName: String? = null
    ): List<BackupFile> {
        if (!config.enabled) {
            return expiredTemporaryBackupsToDelete(backups, nowMillis)
                .filterNot { it.name == protectedBackupName }
        }

        // Permanent and undated backups are retained outside the count limit.
        // Always retain the upload that triggered cleanup, even if the server clock is wrong.
        return backups
            .filterNot(BackupFile::isPermanent)
            .filter { it.modified.time > 0L || it.name == protectedBackupName }
            .sortedWith(
                compareByDescending<BackupFile> { it.name == protectedBackupName }
                    .thenByDescending { it.modified.time }
                    .thenByDescending { it.name }
            )
            .distinctBy(BackupFile::name)
            .drop(config.maxBackups.coerceIn(BackupRetentionConfig.MAX_BACKUPS_RANGE))
            .asReversed()
    }

    fun expiredTemporaryBackupsToDelete(
        backups: List<BackupFile>,
        nowMillis: Long = System.currentTimeMillis(),
        retentionDays: Long = DEFAULT_RETENTION_DAYS,
        minTemporaryBackupsToKeep: Int = DEFAULT_MIN_TEMPORARY_BACKUPS_TO_KEEP
    ): List<BackupFile> {
        val cutoffMillis = nowMillis - retentionDays * 24L * 60L * 60L * 1000L
        return backups
            .filterNot(BackupFile::isPermanent)
            .sortedByDescending { it.modified.time }
            .drop(minTemporaryBackupsToKeep.coerceAtLeast(0))
            .filter { backup ->
                val modifiedMillis = backup.modified.time
                modifiedMillis > 0L && modifiedMillis < cutoffMillis
            }
    }
}

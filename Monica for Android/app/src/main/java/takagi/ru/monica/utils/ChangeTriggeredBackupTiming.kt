package takagi.ru.monica.utils

/**
 * Pure timing rules for change-triggered backups.
 *
 * A change-triggered backup has two independent delays:
 * - the quiet period after the most recent mutation;
 * - the minimum interval since the previous successful upload.
 *
 * Keeping this calculation free of Android/WorkManager types makes the rule
 * deterministic and prevents a mutation from being silently discarded when
 * the minimum interval has not elapsed yet.
 */
internal object ChangeTriggeredBackupTiming {

    private const val MILLIS_PER_MINUTE = 60_000L

    fun requiredDelayMillis(
        nowMillis: Long,
        lastBackupTimeMillis: Long,
        quietMinutes: Int,
        minIntervalMinutes: Int
    ): Long {
        val quietDelay = minutesToMillis(quietMinutes)
        return maxOf(
            quietDelay,
            remainingMinimumIntervalMillis(
                nowMillis = nowMillis,
                lastBackupTimeMillis = lastBackupTimeMillis,
                minIntervalMinutes = minIntervalMinutes
            )
        )
    }

    fun remainingMinimumIntervalMillis(
        nowMillis: Long,
        lastBackupTimeMillis: Long,
        minIntervalMinutes: Int
    ): Long {
        if (lastBackupTimeMillis <= 0L || minIntervalMinutes <= 0) return 0L

        // A wall-clock rollback must not postpone the upload indefinitely.
        // Treat it as an elapsed-time reset and let the quiet period govern.
        if (nowMillis < lastBackupTimeMillis) return 0L

        // Subtract before comparing instead of adding to lastBackupTimeMillis;
        // this avoids overflow for corrupted/imported timestamps near Long.MAX_VALUE.
        val elapsedMillis = nowMillis - lastBackupTimeMillis
        val intervalMillis = minutesToMillis(minIntervalMinutes)
        return (intervalMillis - elapsedMillis).coerceAtLeast(0L)
    }

    private fun minutesToMillis(minutes: Int): Long {
        val safeMinutes = minutes.coerceAtLeast(0).toLong()
        val maxMinutes = Long.MAX_VALUE / MILLIS_PER_MINUTE
        return safeMinutes.coerceAtMost(maxMinutes) * MILLIS_PER_MINUTE
    }
}

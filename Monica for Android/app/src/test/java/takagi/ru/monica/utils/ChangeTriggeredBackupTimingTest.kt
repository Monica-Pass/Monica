package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeTriggeredBackupTimingTest {

    @Test
    fun `first change waits only for quiet period`() {
        assertEquals(
            2 * 60_000L,
            ChangeTriggeredBackupTiming.requiredDelayMillis(
                nowMillis = 1_000_000L,
                lastBackupTimeMillis = 0L,
                quietMinutes = 2,
                minIntervalMinutes = 15
            )
        )
    }

    @Test
    fun `recent backup delays until both quiet period and minimum interval`() {
        assertEquals(
            400_000L,
            ChangeTriggeredBackupTiming.requiredDelayMillis(
                nowMillis = 10_000_000L,
                lastBackupTimeMillis = 9_500_000L,
                quietMinutes = 2,
                minIntervalMinutes = 15
            )
        )
    }

    @Test
    fun `elapsed interval does not add an extra delay`() {
        assertEquals(
            2 * 60_000L,
            ChangeTriggeredBackupTiming.requiredDelayMillis(
                nowMillis = 20_000_000L,
                lastBackupTimeMillis = 19_000_000L,
                quietMinutes = 2,
                minIntervalMinutes = 15
            )
        )
    }

    @Test
    fun `clock rollback is fail open`() {
        assertEquals(
            2 * 60_000L,
            ChangeTriggeredBackupTiming.requiredDelayMillis(
                nowMillis = 5_000_000L,
                lastBackupTimeMillis = 6_000_000L,
                quietMinutes = 2,
                minIntervalMinutes = 15
            )
        )
    }

    @Test
    fun nearMaxTimestampDoesNotOverflow() {
        assertEquals(
            899_990L,
            ChangeTriggeredBackupTiming.remainingMinimumIntervalMillis(
                nowMillis = Long.MAX_VALUE,
                lastBackupTimeMillis = Long.MAX_VALUE - 10L,
                minIntervalMinutes = 15
            )
        )
    }
}

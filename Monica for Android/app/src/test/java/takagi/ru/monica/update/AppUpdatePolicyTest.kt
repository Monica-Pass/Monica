package takagi.ru.monica.update

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.UpdateCheckResult

class AppUpdatePolicyTest {
    private val available = UpdateCheckResult("1.0.327", "v1.0.328", null, "https://example.invalid", null, null, null, true,
        releaseIdentity = "standard:v1.0.328")

    @Test fun automaticChecksDefaultOnAndRespectTheDailyAttemptBoundary() {
        val initial = UpdatePreferencesState()
        assertTrue(AppUpdatePolicy.shouldCheck(initial, 100L))
        val attempted = initial.copy(lastAttemptAtMillis = 100L)
        assertFalse(AppUpdatePolicy.shouldCheck(attempted, 100L))
        assertFalse(AppUpdatePolicy.shouldCheck(attempted, 99L + AppUpdatePolicy.CHECK_INTERVAL_MILLIS))
        assertTrue(AppUpdatePolicy.shouldCheck(attempted, 100L + AppUpdatePolicy.CHECK_INTERVAL_MILLIS))
    }

    @Test fun disabledAutomaticChecksNeverFetchButManualChecksBypassAllSuppression() {
        val disabled = UpdatePreferencesState(false, Long.MAX_VALUE, available.releaseIdentity)
        assertFalse(AppUpdatePolicy.shouldCheck(disabled, 100L))
        assertTrue(AppUpdatePolicy.shouldCheck(disabled, 100L, manual = true))
        assertFalse(AppUpdatePolicy.shouldPresent(available, disabled))
        assertTrue(AppUpdatePolicy.shouldPresent(available, disabled, manual = true))
        assertTrue(AppUpdatePolicy.shouldPresent(available.copy(isUpdateAvailable = false), disabled, manual = true))
    }

    @Test fun clockRollbackAllowsOneFreshAttemptWithoutPermanentlySuppressingChecks() {
        val previous = UpdatePreferencesState(lastAttemptAtMillis = 200L)
        assertTrue(AppUpdatePolicy.shouldCheck(previous, 100L))
        assertFalse(AppUpdatePolicy.shouldCheck(previous.copy(lastAttemptAtMillis = 100L), 101L))
    }

    @Test fun skippingMatchesTheExactReleaseAndChannelOnly() {
        val skipped = UpdatePreferencesState(skippedReleaseIdentity = available.releaseIdentity)
        assertFalse(AppUpdatePolicy.shouldPresent(available, skipped))
        assertTrue(AppUpdatePolicy.shouldPresent(available.copy(releaseIdentity = "standard:v1.0.328b"), skipped))
        assertTrue(AppUpdatePolicy.shouldPresent(available.copy(releaseIdentity = "fdroid:35", isFdroid = true), skipped))
        assertFalse(AppUpdatePolicy.shouldPresent(available.copy(isUpdateAvailable = false), UpdatePreferencesState()))
    }

    @Test fun channelIdentityUsesTheApplicationIdSuffixAndNotArbitrarySubstring() {
        assertEquals(UpdateChannel.FDROID, UpdateChannel.defaultForApplicationId("takagi.ru.monica.fdroid"))
        assertEquals(UpdateChannel.FDROID, UpdateChannel.defaultForApplicationId("takagi.ru.monica.updates327.fdroid"))
        assertEquals(UpdateChannel.STANDARD, UpdateChannel.defaultForApplicationId("takagi.ru.monica"))
        assertEquals(UpdateChannel.STANDARD, UpdateChannel.defaultForApplicationId("takagi.ru.monica.fdroid.example"))
    }
}

package takagi.ru.monica.security

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillAuthenticationActivity
import takagi.ru.monica.autofill_ng.AutofillSaveTransparentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real main-process Activities with a stalled local storage boundary. This does not inject a
 * Keystore/provider failure, authenticate a request, or exercise the separate :ime process.
 * Existing raw secure preferences are read only; no password, key alias, or vault is created.
 */
class SecureSecondaryEntryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun authenticationEntryTimesOutWithoutBlockingMainOrQueueingRetry() = runBlocking {
        assertBlockedSecondaryEntry(AutofillAuthenticationActivity::class.java)
    }

    @Test fun saveEntryTimesOutWithoutBlockingMainOrQueueingRetry() = runBlocking {
        assertBlockedSecondaryEntry(AutofillSaveTransparentActivity::class.java)
    }

    private suspend fun <A : Activity> assertBlockedSecondaryEntry(activityClass: Class<A>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Application schedules secure maintenance at 1.2 s and 2.5 s. Let those callers
        // start and relinquish the worker before this test owns the recovery monitor.
        delay(3_000)
        awaitWorkerExit()
        SessionManager.markLocked()
        SecondarySessionManager.markLocked()
        PreparedSecureStore.invalidate()

        val recovery = LocalVaultRecovery(context)
        val names = setOf(SecurePreferencesStore.MONICA, SecurePreferencesStore.BITWARDEN) +
            listOfNotNull(recovery.activeStore()?.first)
        val rawStores = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val before = rawStores.mapValues { (_, raw) -> raw.all.toMap() }
        fun assertPreservedAndLocked() {
            rawStores.forEach { (name, raw) ->
                assertTrue("Raw secure preferences changed: $name", before.getValue(name) == raw.all)
            }
            assertFalse(SessionManager.isUnlocked.value)
            assertFalse(SecondarySessionManager.isUnlocked.value)
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertFalse(SecureStorageStartup.readyForMaintenance)
        }

        val lock = LocalVaultRecovery::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(recovery)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderExpired = AtomicBoolean(false)
        val holder = Thread({
            synchronized(lock) {
                entered.countDown()
                // A finite fail-safe also lets a regressed synchronous Activity eventually exit.
                holderExpired.set(!release.await(60, TimeUnit.SECONDS))
            }
        }, "secondary-entry-recovery-lock").apply { isDaemon = true; start() }
        val resumed = AtomicReference<Activity?>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity.javaClass == activityClass && stage == Stage.RESUMED) resumed.set(activity)
        }
        var scenario: ActivityScenario<A>? = null
        try {
            assertTrue("Recovery monitor was not acquired", entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
            val launchStarted = SystemClock.elapsedRealtime()
            scenario = ActivityScenario.launch(activityClass)
            withTimeout(5_000) {
                while (!SecureStorageStartup.operationInProgress) delay(10)
            }
            // Check responsiveness while storage is still waiting, before an error UI exists.
            repeat(3) {
                assertMainThreadResponds()
                delay(200)
            }
            compose.waitUntil(timeoutMillis = 25_000) { blockedPageExists() }
            assertTrue(
                "Entry should exercise the 15-second deadline",
                SystemClock.elapsedRealtime() - launchStarted >= 14_000
            )
            compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
            assertTrue("Timed-out provider must retain its worker slot", SecureStorageStartup.operationInProgress)
            assertPreservedAndLocked()

            repeat(2) {
                val previous = resumed.get()
                assertNotNull("The real entry Activity must be resumed", previous)
                compose.onNodeWithTag("secure_startup_retry").assertIsDisplayed().performClick()
                // Waiting for destruction and a different resumed Activity prevents the old
                // blocked page from falsely satisfying this check. A queued retry takes 15 s.
                compose.waitUntil(timeoutMillis = 5_000) {
                    resumed.get() !== previous && previous!!.isDestroyed && blockedPageExists()
                }
                compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
                compose.onNodeWithTag("secure_startup_diagnostic").assertIsDisplayed()
                assertMainThreadResponds()
                assertTrue(SecureStorageStartup.operationInProgress)
                assertPreservedAndLocked()
            }
        } finally {
            release.countDown()
            holder.join(5_000)
            try {
                awaitWorkerExit()
            } finally {
                try {
                    scenario?.close()
                } finally {
                    instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                }
            }
        }
        assertFalse("Recovery monitor fail-safe expired before the assertions finished", holderExpired.get())
        assertFalse(holder.isAlive)
        assertPreservedAndLocked()
    }

    private fun blockedPageExists(): Boolean = compose.onAllNodesWithTag("secure_startup_blocked")
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun assertMainThreadResponds() {
        val response = CountDownLatch(1)
        assertTrue(Handler(Looper.getMainLooper()).post { response.countDown() })
        assertTrue("Secure startup blocked the main thread", response.await(2, TimeUnit.SECONDS))
    }

    private suspend fun awaitWorkerExit() = withTimeout(20_000) {
        while (SecureStorageStartup.operationInProgress) delay(10)
    }
}

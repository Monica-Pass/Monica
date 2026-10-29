package takagi.ru.monica.security

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SecureStartupActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun timeoutDrawsRealRecoveryPageWhileProviderStillHoldsRecoveryLock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = context.getSharedPreferences(SecurePreferencesStore.MONICA, 0)
        val before = raw.all.toMap()
        val recovery = LocalVaultRecovery(context)
        val lock = LocalVaultRecovery::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(recovery)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                entered.countDown()
                check(release.await(35, TimeUnit.SECONDS))
            }
        }.apply { isDaemon = true; start() }
        check(entered.await(5, TimeUnit.SECONDS))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(timeoutMillis = 25_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag("secure_startup_blocked")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
                compose.onNodeWithTag("secure_startup_retry").assertIsDisplayed().performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag("secure_startup_blocked")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("secure_startup_diagnostic").assertIsDisplayed()
                assertTrue(SecureStorageStartup.operationInProgress)
                assertFalse(SecureStorageStartup.readyForMaintenance)
                assertFalse(SessionManager.isUnlocked.value)
                assertEquals(before, raw.all)
            }
        } finally {
            release.countDown()
            holder.join(5_000)
            withTimeout(5_000) { while (SecureStorageStartup.operationInProgress) delay(10) }
        }
        assertFalse(SecureStorageStartup.readyForMaintenance)
        assertEquals(before, raw.all)
    }
}

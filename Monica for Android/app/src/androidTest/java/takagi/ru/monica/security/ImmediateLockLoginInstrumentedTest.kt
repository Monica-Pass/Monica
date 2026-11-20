package takagi.ru.monica.security

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.SettingsManager

/** Full main-activity login with the real application sync observer, in a task-owned package only. */
class ImmediateLockLoginInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val password = "Synthetic-Immediate-Lock-329!"
    private val title = "Synthetic immediate-lock retained record"
    private lateinit var security: SecurityManager
    private lateinit var settings: SettingsManager

    @Before fun prepareOwnedFixture() = runBlocking {
        check(context.packageName in setOf("takagi.ru.monica.immediatedebugmain20261120", "takagi.ru.monica.immediatedebugfd20261120"))
        check(InstrumentationRegistry.getArguments().getString("immediateLockFixture") == "yes")
        check(Process.myUid() / 100000 > 0)
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword(password))
        assertTrue(security.unlockVaultWithPassword(password))
        settings = SettingsManager(context)
        settings.updateQuickSetupCompleted(true)
        settings.updateAutoLockMinutes(0)
        SessionManager.updateAutoLockTimeout(0)
        val dao = PasswordDatabase.getDatabase(context).passwordEntryDao()
        if (dao.getAllPasswordEntriesSync().none { it.title == title }) {
            dao.insertPasswordEntry(PasswordEntry(title = title, website = "https://example.test", username = "synthetic",
                password = security.encryptData("Synthetic retained secret")))
        }
        SessionManager.markLocked()
    }

    @After fun closeOwnedActivity() {
        scenario?.close()
        SessionManager.markLocked()
    }

    private fun label(id: Int) = context.getString(id)
    private fun waitForLogin() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.unlock)).assertIsDisplayed()
    }

    private fun unlock(input: String = password) {
        waitForLogin()
        compose.onNode(hasSetTextAction()).performTextReplacement(input)
        compose.onNodeWithText(label(R.string.unlock)).performClick()
    }

    private fun assertStableUnlockedRecord() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        val generation = SessionManager.lockGeneration.value
        val deadline = SystemClock.elapsedRealtime() + 6_500
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("Foreground sync revoked a freshly authenticated immediate-lock session", SessionManager.isUnlocked.value)
            assertEquals(generation, SessionManager.lockGeneration.value)
            SystemClock.sleep(50)
        }
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun realLoginSurvivesSyncAndReentryStillRequiresPassword() {
        for (syncEnabled in listOf(false, true)) {
            context.getSharedPreferences("monica-device-sync", 0).edit()
                .putBoolean("automatic-enabled", syncEnabled).commit()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            unlock("Wrong-synthetic-password")
            compose.waitUntil(20_000) { compose.onAllNodesWithText(label(R.string.error_invalid_password)).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNodeWithText(title).assertDoesNotExist()
            unlock()
            assertStableUnlockedRecord()

            scenario!!.moveToState(Lifecycle.State.CREATED)
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            waitForLogin()
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNodeWithText(title).assertDoesNotExist()
            unlock()
            assertStableUnlockedRecord()

            compose.runOnUiThread { SessionManager.markLocked() }
            waitForLogin()
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNodeWithText(title).assertDoesNotExist()
            unlock()
            assertStableUnlockedRecord()
            val rows = runBlocking { PasswordDatabase.getDatabase(context).passwordEntryDao().getAllPasswordEntriesSync() }
            assertEquals("Synthetic retained secret", security.decryptData(rows.single { it.title == title }.password))
            scenario!!.close()
            scenario = null
            SessionManager.markLocked()
        }
    }
}

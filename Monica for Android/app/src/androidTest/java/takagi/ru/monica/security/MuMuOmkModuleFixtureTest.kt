package takagi.ru.monica.security

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import java.io.File

/**
 * Host-ordered phases for a newly created synthetic user on the isolated OMK test emulator.
 * This fixture never removes or replaces an alias, injects provider faults, or changes modules.
 * The host must independently establish that real OMK is active between seed and reopen.
 * Original ciphertext snapshots remain private evidence; whole-user cleanup belongs to the host.
 */
class MuMuOmkModuleFixtureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun seedOriginalVault() = runBlocking {
        val context = authorizedContext()
        val root = fixtureRoot(context)
        check(!root.exists()) { "Existing OMK fixture must not be overwritten" }
        awaitApplicationStartup()
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        val security = SecurityManager(context)
        check(!security.isMasterPasswordSet()) { "Existing vault must be preserved" }
        check(LocalVaultRecovery(context).activeStore() == null && !envelopeFile(context).exists()) {
            "Existing recovery material must be preserved"
        }
        val database = PasswordDatabase.getDatabase(context)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM password_entries").use {
            check(it.moveToFirst() && it.getLong(0) == 0L) { "Existing password rows must be preserved" }
        }
        check(root.mkdir()) { "Could not reserve private OMK fixture directory" }
        val marker = JSONObject()
            .put("version", 1)
            .put("userId", Process.myUid() / 100_000)
            .put("uid", Process.myUid())
            .put("packageName", context.packageName)
            .put("phase", "initializing")
        markerFile(context).writeText(marker.toString(), Charsets.UTF_8)

        assertTrue(security.setMasterPassword(MASTER_PASSWORD))
        security.putProtectedString(PROTECTED_KEY, PROTECTED_SECRET)
        val ciphertext = security.encryptData(ORIGINAL_SECRET)
        assertTrue("Original fixture row must use MDK encryption", ciphertext.startsWith("MDK|"))
        val row = PasswordEntry(
            title = ORIGINAL_TITLE,
            website = "https://omk-fixture.invalid",
            username = ORIGINAL_USERNAME,
            password = ciphertext
        )
        val rowId = database.passwordEntryDao().insertPasswordEntry(row)
        assertTrue(rowId > 0)
        assertEquals(ciphertext, database.passwordEntryDao().getPasswordEntryById(rowId)?.password)
        assertEquals(ORIGINAL_SECRET, security.decryptData(ciphertext))
        assertEquals(PROTECTED_SECRET, security.getProtectedString(PROTECTED_KEY))
        val mdkPasswordBlob = checkNotNull(security.getProtectedString("mdk_password_blob"))

        finishSeedAfterNormalLogin(context, marker, database, rowId, ciphertext, mdkPasswordBlob)
    }

    /** Complete only this fixture's interrupted baseline; do not reset or replace its vault. */
    @Test fun resumeInterruptedSeed() = runBlocking {
        val context = authorizedContext()
        val marker = JSONObject(markerFile(context).readText(Charsets.UTF_8))
        check(marker.getInt("version") == 1 && marker.getString("phase") == "initializing")
        check(marker.getInt("uid") == Process.myUid())
        check(marker.getInt("userId") == Process.myUid() / 100_000)
        check(marker.getString("packageName") == context.packageName)
        check(!preferencesSnapshot(context).exists() && !envelopeSnapshot(context).exists())
        awaitApplicationStartup()
        val security = SecurityManager(context)
        check(security.isMasterPasswordSet())
        check(LocalVaultRecovery(context).activeStore() == null)
        val database = PasswordDatabase.getDatabase(context)
        val rowId = database.openHelper.readableDatabase.query("SELECT id FROM password_entries").use {
            check(it.moveToFirst())
            val id = it.getLong(0)
            check(!it.moveToNext()) { "Interrupted seed must contain exactly its original synthetic row" }
            id
        }
        val row = checkNotNull(database.passwordEntryDao().getPasswordEntryById(rowId))
        assertEquals(ORIGINAL_TITLE, row.title)
        assertEquals(ORIGINAL_USERNAME, row.username)
        assertTrue(row.password.startsWith("MDK|"))
        assertEquals(PROTECTED_SECRET, security.getProtectedString(PROTECTED_KEY))
        val mdkPasswordBlob = checkNotNull(security.getProtectedString("mdk_password_blob"))
        marker.put("resumedAfterInterruptedSeed", true)
        finishSeedAfterNormalLogin(context, marker, database, rowId, row.password, mdkPasswordBlob)
    }

    private suspend fun finishSeedAfterNormalLogin(
        context: Context,
        marker: JSONObject,
        database: PasswordDatabase,
        rowId: Long,
        ciphertext: String,
        mdkPasswordBlob: String
    ) {
        // Establish an actually usable pre-module baseline. Main's first ordinary startup
        // can legitimately initialize the Bitwarden settings mirror in the recovery envelope.
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(25_000) { normalLoginVisible() }
            awaitHostWindowFocus(scenario)
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
            val initialized = SecurityManager(context)
            // Observe the real Main initialization; do not call the mirror initializer here.
            compose.waitUntil(25_000) {
                initialized.getProtectedString("bitwarden_settings_recovery_v1") != null
            }
            val baselineRow = checkNotNull(database.passwordEntryDao().getPasswordEntryById(rowId))
            assertEquals(ciphertext, baselineRow.password)
            assertEquals(ORIGINAL_SECRET, initialized.decryptData(baselineRow.password))
            assertEquals(PROTECTED_SECRET, initialized.getProtectedString(PROTECTED_KEY))
            assertEquals(mdkPasswordBlob, initialized.getProtectedString("mdk_password_blob"))
            assertNull(LocalVaultRecovery(context).activeStore())
            compose.waitForIdle()
        }
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        val (originalPreferences, originalEnvelope) = stableOriginalFiles(context)
        preferencesSnapshot(context).writeBytes(originalPreferences)
        envelopeSnapshot(context).writeBytes(originalEnvelope)
        marker.put("rowId", rowId)
            .put("rowCiphertext", ciphertext)
            .put("rowSecret", ORIGINAL_SECRET)
            .put("mdkPasswordBlob", mdkPasswordBlob)
            .put("protectedKey", PROTECTED_KEY)
            .put("protectedSecret", PROTECTED_SECRET)
            .put("baselineMainLoginVerified", true)
            .put("phase", "seeded")
        markerFile(context).writeText(marker.toString(), Charsets.UTF_8)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
        assertArrayEquals(originalEnvelope, envelopeFile(context).readBytes())
        // Exit with original data intact. All OMK/Keystore environment changes are external.
    }

    @Test fun reopenOriginalVault() = runBlocking {
        val context = authorizedContext()
        val marker = JSONObject(markerFile(context).readText(Charsets.UTF_8))
        check(marker.getInt("version") == 1 && marker.getString("phase") == "seeded")
        check(marker.getInt("uid") == Process.myUid())
        check(marker.getInt("userId") == Process.myUid() / 100_000)
        check(marker.getString("packageName") == context.packageName)
        val originalPreferences = preferencesSnapshot(context).readBytes()
        val originalEnvelope = envelopeSnapshot(context).readBytes()
        fun assertOriginalFilesBeforeAuthentication() {
            assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
            assertArrayEquals(originalEnvelope, envelopeFile(context).readBytes())
        }
        assertOriginalFilesBeforeAuthentication()
        awaitApplicationStartup()
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        val recovery = LocalVaultRecovery(context)
        assertNull(recovery.activeStore())
        var recovered = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(25_000) { tagExists("secure_startup_blocked") || normalLoginVisible() }
            awaitHostWindowFocus(scenario)
            // Startup must not silently reset the original ciphertext before either route.
            assertOriginalFilesBeforeAuthentication()
            if (tagExists("secure_startup_blocked")) {
                compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
                compose.onNodeWithTag("secure_startup_recover").assertIsDisplayed()
                compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(MASTER_PASSWORD)
                compose.onNodeWithTag("secure_startup_recover").performClick()
                compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
                assertFalse(SessionManager.isUnlocked.value)
                compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
                compose.waitUntil(25_000) { normalLoginVisible() && recovery.activeStore() != null }
                recovered = true
                assertNotNull(recovery.activeStore())
                // Confirmed recovery preserves the source files before normal login resumes.
                assertOriginalFilesBeforeAuthentication()
            }
            awaitHostWindowFocus(scenario)
            assertTrue(normalLoginVisible())
            assertFalse("The original master password must still be required", SessionManager.isUnlocked.value)
            assertFalse(SecondarySessionManager.isUnlocked.value)
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }

            val security = SecurityManager(context)
            val dao = PasswordDatabase.getDatabase(context).passwordEntryDao()
            val original = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
            assertEquals(ORIGINAL_TITLE, original.title)
            assertEquals(ORIGINAL_USERNAME, original.username)
            assertEquals(marker.getString("rowCiphertext"), original.password)
            assertEquals(marker.getString("rowSecret"), security.decryptData(original.password))
            assertEquals(marker.getString("mdkPasswordBlob"), security.getProtectedString("mdk_password_blob"))
            assertEquals(marker.getString("protectedSecret"), security.getProtectedString(marker.getString("protectedKey")))

            val newCiphertext = security.encryptData(NEW_SECRET)
            assertTrue("Subsequent writes must still use MDK encryption", newCiphertext.startsWith("MDK|"))
            val newId = dao.insertPasswordEntry(PasswordEntry(
                title = NEW_TITLE,
                website = "https://omk-fixture.invalid/after",
                username = "synthetic-omk-after-user",
                password = newCiphertext
            ))
            assertTrue(newId > 0 && newId != original.id)
            val inserted = checkNotNull(dao.getPasswordEntryById(newId))
            assertEquals(NEW_TITLE, inserted.title)
            assertEquals(newCiphertext, inserted.password)
            assertEquals(NEW_SECRET, security.decryptData(inserted.password))
            assertEquals(original.password, dao.getPasswordEntryById(original.id)?.password)
            // Normal authentication can maintain the active recovery envelope. The immutable
            // snapshots and (when recovery was used) the retired source prefs must remain.
            assertArrayEquals(originalPreferences, preferencesSnapshot(context).readBytes())
            assertArrayEquals(originalEnvelope, envelopeSnapshot(context).readBytes())
            if (recovered) assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
            marker.put("phase", "reopened_and_write_verified")
                .put("usedConfirmedRecovery", recovered)
                .put("newRowId", newId)
                .put("newRowCiphertext", newCiphertext)
                .put("reopenProcessId", Process.myPid())
            markerFile(context).writeText(marker.toString(), Charsets.UTF_8)
        }
        // Keep both records, source files and private evidence for the host's final inspection.
    }

    /** The host must stop the fixture app process, without clearing data, after reopen passes. */
    @Test fun verifyAfterColdRestart() = runBlocking {
        val context = authorizedContext()
        val marker = JSONObject(markerFile(context).readText(Charsets.UTF_8))
        check(marker.getInt("version") == 1 && marker.getString("phase") == "reopened_and_write_verified")
        check(marker.getInt("uid") == Process.myUid())
        check(marker.getInt("userId") == Process.myUid() / 100_000)
        check(marker.getString("packageName") == context.packageName)
        check(marker.getInt("reopenProcessId") != Process.myPid()) {
            "Cold verification requires a different app process from reopen"
        }
        assertFalse("A cold process must begin locked", SessionManager.isUnlocked.value)
        assertFalse(SecondarySessionManager.isUnlocked.value)
        assertFalse("Cold verification must not inherit an unlocked MDK", SecurityManager.hasRuntimeUnlockCache())
        val retiredPreferences = if (marker.getBoolean("usedConfirmedRecovery")) {
            preferencesSnapshot(context).readBytes()
        } else null
        retiredPreferences?.let { assertArrayEquals(it, preferencesFile(context).readBytes()) }
        awaitApplicationStartup()
        PreparedSecureStore.invalidate()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(25_000) { tagExists("secure_startup_blocked") || normalLoginVisible() }
            awaitHostWindowFocus(scenario)
            assertFalse("Storage blocked again after restart; this phase must not repeat recovery",
                tagExists("secure_startup_blocked"))
            assertTrue(normalLoginVisible())
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }

            val security = SecurityManager(context)
            val dao = PasswordDatabase.getDatabase(context).passwordEntryDao()
            val original = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
            val afterModule = checkNotNull(dao.getPasswordEntryById(marker.getLong("newRowId")))
            assertEquals(ORIGINAL_TITLE, original.title)
            assertEquals(ORIGINAL_USERNAME, original.username)
            assertEquals(marker.getString("rowCiphertext"), original.password)
            assertEquals(marker.getString("rowSecret"), security.decryptData(original.password))
            assertEquals(NEW_TITLE, afterModule.title)
            assertEquals("synthetic-omk-after-user", afterModule.username)
            assertEquals(marker.getString("newRowCiphertext"), afterModule.password)
            assertEquals(NEW_SECRET, security.decryptData(afterModule.password))
            assertEquals(marker.getString("mdkPasswordBlob"), security.getProtectedString("mdk_password_blob"))
            assertEquals(marker.getString("protectedSecret"), security.getProtectedString(marker.getString("protectedKey")))

            val ciphertext = security.encryptData(COLD_SECRET)
            assertTrue("Cold-process writes must retain MDK encryption", ciphertext.startsWith("MDK|"))
            val id = dao.insertPasswordEntry(PasswordEntry(
                title = COLD_TITLE,
                website = "https://omk-fixture.invalid/cold",
                username = "synthetic-omk-cold-user",
                password = ciphertext
            ))
            assertTrue(id > 0 && id != original.id && id != afterModule.id)
            val inserted = checkNotNull(dao.getPasswordEntryById(id))
            assertEquals(COLD_TITLE, inserted.title)
            assertEquals(ciphertext, inserted.password)
            assertEquals(COLD_SECRET, security.decryptData(inserted.password))
            assertEquals(original.password, dao.getPasswordEntryById(original.id)?.password)
            assertEquals(afterModule.password, dao.getPasswordEntryById(afterModule.id)?.password)
            retiredPreferences?.let { assertArrayEquals(it, preferencesFile(context).readBytes()) }
            // The active envelope may be maintained by ordinary authentication. Do not compare
            // it with the seed envelope or invoke recovery to conceal a persistence failure.
            marker.put("phase", "cold_restart_write_verified")
                .put("coldProcessId", Process.myPid())
                .put("coldRowId", id)
                .put("coldRowCiphertext", ciphertext)
            markerFile(context).writeText(marker.toString(), Charsets.UTF_8)
        }
    }

    private fun authorizedContext(): Context {
        val requested = InstrumentationRegistry.getArguments().getString("syntheticOmkUser")
        assumeTrue("Requires an explicitly created synthetic OMK test user", requested != null)
        val userId = checkNotNull(requested?.toIntOrNull()) { "Invalid syntheticOmkUser" }
        check(userId > 0 && userId == Process.myUid() / 100_000) {
            "Refusing to run outside the selected synthetic user or in Owner user 0"
        }
        return InstrumentationRegistry.getInstrumentation().targetContext.also {
            check(it.applicationInfo.uid == Process.myUid()) { "Target and fixture UID must match" }
        }
    }

    private suspend fun awaitApplicationStartup() {
        delay(3_000)
        withTimeout(20_000) {
            while (SecureStorageStartup.operationInProgress || ConfirmedStorageRecovery.inProgress) delay(10)
        }
    }

    private suspend fun stableOriginalFiles(context: Context): Pair<ByteArray, ByteArray> = withTimeout(10_000) {
        var previous: Pair<ByteArray, ByteArray>? = null
        var stableSamples = 0
        while (stableSamples < 4) {
            val current = runCatching {
                val preferences = preferencesFile(context)
                val envelope = envelopeFile(context)
                check(!File(preferences.path + ".bak").exists() && !File(envelope.path + ".bak").exists())
                preferences.readBytes() to envelope.readBytes()
            }.getOrNull()
            if (current != null && current.first.isNotEmpty() && current.second.isNotEmpty() &&
                previous?.first?.contentEquals(current.first) == true &&
                previous?.second?.contentEquals(current.second) == true) {
                stableSamples++
            } else {
                stableSamples = 0
            }
            previous = current
            if (stableSamples < 4) delay(250)
        }
        checkNotNull(previous)
    }

    private fun awaitHostWindowFocus(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(5_000) {
            var focused = false
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            focused
        }
    }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun normalLoginVisible() = !tagExists("secure_startup_blocked") &&
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1

    private fun fixtureRoot(context: Context) = File(context.filesDir, "mumu-omk-module-fixture-325")
    private fun markerFile(context: Context) = File(fixtureRoot(context), "fixture.json")
    private fun preferencesFile(context: Context) =
        File(context.applicationInfo.dataDir, "shared_prefs/${SecurePreferencesStore.MONICA}.xml")
    private fun envelopeFile(context: Context) = File(context.filesDir, "local-vault-recovery/recovery.json")
    private fun preferencesSnapshot(context: Context) = File(fixtureRoot(context), "monica_secure_prefs.xml.original")
    private fun envelopeSnapshot(context: Context) = File(fixtureRoot(context), "recovery.json.original")

    private companion object {
        const val MASTER_PASSWORD = "Synthetic-Real-OMK-325!Only-Test"
        const val PROTECTED_KEY = "mumu_real_omk_fixture_325"
        const val PROTECTED_SECRET = "synthetic-omk-protected-secret-preserved-325"
        const val ORIGINAL_TITLE = "Synthetic original vault before OMK 325"
        const val ORIGINAL_USERNAME = "synthetic-omk-original-user"
        const val ORIGINAL_SECRET = "Synthetic-original-OMK-row-325!Preserve-MDK"
        const val NEW_TITLE = "Synthetic new record after OMK 325"
        const val NEW_SECRET = "Synthetic-new-OMK-row-325!Read-Back"
        const val COLD_TITLE = "Synthetic new record after OMK cold restart 325"
        const val COLD_SECRET = "Synthetic-cold-OMK-row-325!Read-Back"
    }
}

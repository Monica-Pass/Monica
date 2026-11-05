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
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
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
import java.security.KeyStore

/**
 * Two explicitly selected phases for a newly created disposable Android user, never Owner or 10.
 * The host observes real :ime entry points between phases and disposes of the whole user later.
 * Only the fixture UID's AndroidX master alias is deliberately replaced; other aliases and all
 * original vault files are retained. This models actual key replacement, not a provider timeout.
 */
class MuMuKeystoreRecoveryFixtureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun seedReplacedMasterKey() = runBlocking {
        val context = authorizedContext()
        val root = fixtureRoot(context)
        check(!root.exists()) { "Existing fixture must be preserved; use a new disposable user" }
        awaitApplicationStartup()
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        val security = SecurityManager(context)
        check(!security.isMasterPasswordSet()) { "This user already has a vault; preserve it" }
        val recovery = LocalVaultRecovery(context)
        check(recovery.activeStore() == null && !envelopeFile(context).exists()) {
            "Existing recovery material must be preserved"
        }
        val database = PasswordDatabase.getDatabase(context)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM password_entries").use {
            check(it.moveToFirst() && it.getLong(0) == 0L) { "Existing password rows must be preserved" }
        }
        check(root.mkdir()) { "Could not reserve a private fixture directory" }
        val marker = JSONObject()
            .put("version", 1)
            .put("userId", Process.myUid() / 100_000)
            .put("uid", Process.myUid())
            .put("packageName", context.packageName)
            .put("phase", "initializing")
        markerFile(context).writeText(marker.toString(), Charsets.UTF_8)

        assertTrue(security.setMasterPassword(MASTER_PASSWORD))
        security.putProtectedString(PROTECTED_KEY, PROTECTED_SECRET)
        val ciphertext = security.encryptData(ROW_SECRET)
        assertTrue("Fixture row must use the original MDK", ciphertext.startsWith("MDK|"))
        val entry = PasswordEntry(
            title = ROW_TITLE,
            website = "https://mumu-recovery.invalid",
            username = ROW_USERNAME,
            password = ciphertext
        )
        val rowId = database.passwordEntryDao().insertPasswordEntry(entry)
        assertTrue(rowId > 0)
        assertEquals(ciphertext, database.passwordEntryDao().getPasswordEntryById(rowId)?.password)
        assertEquals(ROW_SECRET, security.decryptData(ciphertext))
        val mdkPasswordBlob = checkNotNull(security.getProtectedString("mdk_password_blob"))
        val originalPreferences = preferencesFile(context).readBytes()
        val originalEnvelope = envelopeFile(context).readBytes()
        check(originalPreferences.isNotEmpty() && originalEnvelope.isNotEmpty())
        preferencesSnapshot(context).writeBytes(originalPreferences)
        envelopeSnapshot(context).writeBytes(originalEnvelope)
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = keystore.aliases().toList().sorted()
        val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
        check(alias in aliases)
        marker.put("phase", "seeded")
            .put("rowId", rowId)
            .put("rowCiphertext", ciphertext)
            .put("mdkPasswordBlob", mdkPasswordBlob)
            .put("rowSecret", ROW_SECRET)
            .put("protectedKey", PROTECTED_KEY)
            .put("protectedSecret", PROTECTED_SECRET)
            .put("originalAliases", JSONArray(aliases))
        markerFile(context).writeText(marker.toString(), Charsets.UTF_8)

        // Recheck the exact opt-in immediately before the deliberately destructive test action.
        authorizedContext()
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        keystore.deleteEntry(alias)
        assertFalse(keystore.containsAlias(alias))
        MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        assertTrue(keystore.containsAlias(alias))
        assertEquals(aliases, keystore.aliases().toList().sorted())
        SecurityManager.clearRuntimeUnlockCache()
        PreparedSecureStore.invalidate()
        val result = SecureStorageStartup.prepare(context)
        assertTrue("Replaced master key must block opening the original keysets", result is SecureStartupResult.Blocked)
        assertFalse(SecureStorageStartup.readyForMaintenance)
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecondarySessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        assertNull(recovery.activeStore())
        assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
        assertArrayEquals(originalEnvelope, envelopeFile(context).readBytes())
        marker.put("phase", "blocked")
            .put("blockedReason", (result as SecureStartupResult.Blocked).failure.reason)
        markerFile(context).writeText(marker.toString(), Charsets.UTF_8)
        // No local cleanup: the host now launches the actual keyboard and ImeUnlockActivity.
    }

    @Test fun recoverAndEnterRealMain() = runBlocking {
        val context = authorizedContext()
        val marker = JSONObject(markerFile(context).readText(Charsets.UTF_8))
        check(marker.getInt("version") == 1 && marker.getString("phase") == "blocked")
        check(marker.getInt("uid") == Process.myUid())
        check(marker.getInt("userId") == Process.myUid() / 100_000)
        check(marker.getString("packageName") == context.packageName)
        val originalPreferences = preferencesSnapshot(context).readBytes()
        val originalEnvelope = envelopeSnapshot(context).readBytes()
        assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
        assertArrayEquals(originalEnvelope, envelopeFile(context).readBytes())
        awaitApplicationStartup()
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        val recovery = LocalVaultRecovery(context)
        assertNull(recovery.activeStore())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(25_000) {
                compose.onAllNodesWithTag("secure_startup_blocked")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(MASTER_PASSWORD)
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed().performClick()
            compose.waitUntil(25_000) {
                compose.onAllNodes(hasSetTextAction())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 &&
                    compose.onAllNodesWithTag("secure_startup_blocked")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                    recovery.activeStore() != null
            }
            assertNotNull(recovery.activeStore())
            assertFalse(SessionManager.isUnlocked.value)
            assertFalse(SecondarySessionManager.isUnlocked.value)
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            // The actual recovery buttons preserve both canonical originals. A subsequent
            // normal password login may refresh the snapshot to match the recovered store.
            assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
            assertArrayEquals(originalEnvelope, envelopeFile(context).readBytes())
            assertFalse("Recovery must still require the normal login", SessionManager.isUnlocked.value)
            compose.onNode(hasSetTextAction()).performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
            val restored = SecurityManager(context)
            val row = checkNotNull(PasswordDatabase.getDatabase(context).passwordEntryDao()
                .getPasswordEntryById(marker.getLong("rowId")))
            assertEquals(ROW_TITLE, row.title)
            assertEquals(ROW_USERNAME, row.username)
            assertEquals(marker.getString("rowCiphertext"), row.password)
            assertEquals(marker.getString("rowSecret"), restored.decryptData(row.password))
            assertEquals(marker.getString("mdkPasswordBlob"), restored.getProtectedString("mdk_password_blob"))
            assertEquals(marker.getString("protectedSecret"), restored.getProtectedString(marker.getString("protectedKey")))
            assertFalse(restored.isBiometricEnabled())
            assertArrayEquals(originalPreferences, preferencesFile(context).readBytes())
            assertArrayEquals(originalEnvelope, envelopeSnapshot(context).readBytes())
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val originalAliases = marker.getJSONArray("originalAliases")
            for (index in 0 until originalAliases.length()) {
                assertTrue("An original alias was removed", keystore.containsAlias(originalAliases.getString(index)))
            }
            marker.put("phase", "recovered_and_logged_in")
            markerFile(context).writeText(marker.toString(), Charsets.UTF_8)
        }
        // Preserve the Room row, old preference file, aliases and snapshots for host inspection.
    }

    private fun authorizedContext(): Context {
        val requested = InstrumentationRegistry.getArguments().getString("syntheticRecoveryUser")
        assumeTrue("Requires an explicitly created disposable test user", requested != null)
        val userId = checkNotNull(requested?.toIntOrNull()) { "Invalid syntheticRecoveryUser" }
        check(userId > 10 && Process.myUid() / 100_000 == userId) {
            "Refusing to change keys outside the explicitly selected disposable user"
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

    private fun fixtureRoot(context: Context) = File(context.filesDir, "mumu-keystore-recovery-325")
    private fun markerFile(context: Context) = File(fixtureRoot(context), "fixture.json")
    private fun preferencesFile(context: Context) =
        File(context.applicationInfo.dataDir, "shared_prefs/${SecurePreferencesStore.MONICA}.xml")
    private fun envelopeFile(context: Context) = File(context.filesDir, "local-vault-recovery/recovery.json")
    private fun preferencesSnapshot(context: Context) = File(fixtureRoot(context), "monica_secure_prefs.xml.original")
    private fun envelopeSnapshot(context: Context) = File(fixtureRoot(context), "recovery.json.original")

    private companion object {
        const val MASTER_PASSWORD = "Synthetic-MuMu-Recovery-325!Only-Test"
        const val PROTECTED_KEY = "mumu_keystore_recovery_fixture_325"
        const val PROTECTED_SECRET = "synthetic-protected-secret-preserved-325"
        const val ROW_TITLE = "Synthetic MuMu Keystore recovery 325"
        const val ROW_USERNAME = "synthetic-mumu-recovery-user"
        const val ROW_SECRET = "Synthetic-row-password-325!Preserve-MDK"
    }
}

package takagi.ru.monica.security

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import takagi.ru.monica.MainActivity

/** Explicit opt-in: this creates synthetic app data in a separately owned test user only. */
class ConfirmedRecoveryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realMainActivityCancelsRejectsWrongPasswordAndRecoversAfterConfirmation() {
        val testUser = InstrumentationRegistry.getArguments().getString("syntheticRecoveryUser")?.toIntOrNull()
        assumeTrue("Run only in an explicitly created disposable test user", testUser != null)
        check(testUser!! > 10 && android.os.Process.myUid() / 100_000 == testUser)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityManager.clearRuntimeUnlockCache()
        val manager = SecurityManager(context)
        check(!manager.isMasterPasswordSet()) { "Test user already has a vault; preserve it" }
        val password = "Synthetic-main-recovery-324!"
        val fixture = "confirmed_activity_fixture_${UUID.randomUUID()}"
        assertTrue(manager.setMasterPassword(password))
        manager.putProtectedString(fixture, "synthetic-preserved-main-activity-secret")
        val raw = context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE)
        assertTrue(raw.edit().remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit())
        SecurityManager.clearRuntimeUnlockCache()
        val original = raw.all.toMap()
        val envelope = File(context.filesDir, "local-vault-recovery/recovery.json")
        val originalEnvelope = envelope.readBytes()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = java.util.Collections.list(ks.aliases()).sorted()
        val recovery = LocalVaultRecovery(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("secure_startup_password")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(password)
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_cancel").performClick()
            assertNull(recovery.activeStore())
            assertEquals(original, raw.all)
            assertArrayEquals(originalEnvelope, envelope.readBytes())
            assertEquals(aliases, java.util.Collections.list(ks.aliases()).sorted())

            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("wrong-synthetic-password")
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("secure_startup_recovery_error")).fetchSemanticsNodes().isNotEmpty() }
            assertNull(recovery.activeStore())
            assertEquals(original, raw.all)
            assertArrayEquals(originalEnvelope, envelope.readBytes())
            assertEquals(aliases, java.util.Collections.list(ks.aliases()).sorted())

            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(password)
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
            compose.waitUntil(25_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasTestTag("secure_startup_blocked")).fetchSemanticsNodes().isEmpty() }
            // The recovered app still requires its normal master-password login.
            compose.onNode(hasSetTextAction()).performTextInput(password)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
            assertNotNull(recovery.activeStore())
            assertEquals(original, raw.all)
            val restored = SecurityManager(context)
            assertTrue(restored.unlockVaultWithPassword(password))
            assertEquals("synthetic-preserved-main-activity-secret", restored.getProtectedString(fixture))
            assertFalse(restored.isBiometricEnabled())
        }
        // Whole-user disposal is performed by the host driver after both distributions finish.
    }
}

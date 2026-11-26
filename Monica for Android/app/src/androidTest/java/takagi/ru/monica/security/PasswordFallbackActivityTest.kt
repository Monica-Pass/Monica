package takagi.ru.monica.security

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.Security
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity

/** Host-owned application/user only. The host kills the process between these phases. */
class PasswordFallbackActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Synthetic-independent-recovery-331!"
    private val marker get() = File(context.filesDir, "password-fallback-activity-331")

    private fun authorize() {
        check(InstrumentationRegistry.getArguments().getString("isolatedRecoveryUser") == "yes")
        check(android.os.Process.myUid() / 100_000 ==
            InstrumentationRegistry.getArguments().getString("expectedRecoveryUser")?.toInt())
        check(context.packageName.startsWith("takagi.ru.monica.resilience"))
    }

    private fun <T> withoutProvider(action: () -> T): T {
        val provider = checkNotNull(Security.getProvider("AndroidKeyStore"))
        val position = Security.getProviders().indexOf(provider) + 1
        try {
            Security.removeProvider(provider.name)
            assertThrows(java.security.KeyStoreException::class.java) { KeyStore.getInstance("AndroidKeyStore") }
            return action()
        } finally { assertEquals(position, Security.insertProviderAt(provider, position)) }
    }

    private fun waitFor(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    }

    private fun login() {
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("secure_startup_blocked").fetchSemanticsNodes(false).isEmpty() &&
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(false).size == 1
        }
        assertFalse("Recovery must not grant a normal session", SessionManager.isUnlocked.value)
        compose.onNode(hasSetTextAction()).performTextInput(password)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
    }

    @Test fun realMainRecoveryRequiresConfirmationAndPreservesOriginalsWithoutProvider() = runBlocking {
        authorize()
        check(!marker.exists())
        // Let scheduled application startup maintenance finish before injecting the failure.
        delay(3_000)
        withTimeout(20_000) { while (SecureStorageStartup.operationInProgress) delay(10) }
        val manager = SecurityManager(context)
        check(!manager.isMasterPasswordSet()) { "Preserve any pre-existing vault" }
        assertTrue(manager.setMasterPassword(password))
        manager.putProtectedString("independent_activity_secret", "Preserved synthetic secret 中文")
        val config = File(context.applicationInfo.dataDir, "shared_prefs/${SecurePreferencesStore.MONICA}.xml")
        val envelope = File(context.filesDir, "local-vault-recovery/recovery.json")
        val configBytes = config.readBytes()
        val envelopeBytes = envelope.readBytes()
        val recovery = LocalVaultRecovery(context)
        SessionManager.markLocked(); SecurityManager.clearRuntimeUnlockCache(); PreparedSecureStore.invalidate()
        withoutProvider {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("secure_startup_password")
                compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(password)
                compose.onNodeWithTag("secure_startup_recover_without_keystore").performScrollTo().performClick()
                compose.onNodeWithTag("secure_startup_recovery_cancel").performClick()
                assertFalse(recovery.usesPasswordStore())
                assertArrayEquals(configBytes, config.readBytes())
                assertArrayEquals(envelopeBytes, envelope.readBytes())
                compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("incorrect")
                compose.onNodeWithTag("secure_startup_recover_without_keystore").performScrollTo().performClick()
                compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
                waitFor("secure_startup_recovery_error")
                assertFalse(recovery.usesPasswordStore())
                compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(password)
                compose.onNodeWithTag("secure_startup_recover_without_keystore").performScrollTo().performClick()
                compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
                login()
                assertTrue(recovery.usesPasswordStore())
                assertArrayEquals(configBytes, config.readBytes())
                assertArrayEquals(envelopeBytes, envelope.readBytes())
                val restored = SecurityManager(context)
                assertEquals("Preserved synthetic secret 中文", restored.getProtectedString("independent_activity_secret"))
                restored.putProtectedString("independent_activity_new", "New synthetic value")
                assertFalse(restored.isBiometricEnabled())
                marker.writeText(android.os.Process.myPid().toString())
            }
        }
    }

    @Test fun coldMainUnlocksSelectedPasswordStoreWithoutProvider() {
        authorize()
        assertNotEquals(marker.readText(), android.os.Process.myPid().toString())
        assertTrue(LocalVaultRecovery(context).usesPasswordStore())
        withoutProvider {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("secure_startup_password")
                compose.onNodeWithTag("secure_startup_recover_without_keystore").assertDoesNotExist()
                compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(password)
                compose.onNodeWithTag("secure_startup_recover").performClick()
                login()
                val restored = SecurityManager(context)
                assertEquals("Preserved synthetic secret 中文", restored.getProtectedString("independent_activity_secret"))
                assertEquals("New synthetic value", restored.getProtectedString("independent_activity_new"))
            }
        }
    }
}

package takagi.ru.monica.security

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.RecoveryProtectionDialog
import takagi.ru.monica.ui.components.RecoveryProtectionSetting

class RecoveryProtectionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var f: BiometricWrapperRecoveryInstrumentedTest.Fixture
    @Before fun prepare() {
        f = BiometricWrapperRecoveryInstrumentedTest.Fixture()
        assertTrue(f.manager.setMasterPassword(BiometricWrapperRecoveryInstrumentedTest.PASSWORD))
    }
    @After fun cleanup() { if (::f.isInitialized) f.close() }
    private fun text(id: Int) = f.context.getString(id)
    private fun capture(name: String) {
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(f.base.filesDir, "recovery-protection-331-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    private fun show(success: AtomicInteger, dismiss: AtomicInteger, large: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else density.fontScale)) {
                MaterialTheme {
                    RecoveryProtectionDialog(f.manager, { dismiss.incrementAndGet() }, { success.incrementAndGet() })
                }
            }
        }
    }
    @Test fun passwordConfirmationVerifiesWithoutChangingOriginalCiphertext() {
        val cipher = f.manager.encryptData("synthetic-ui-original")
        val success = AtomicInteger(); val dismiss = AtomicInteger()
        show(success, dismiss)
        compose.onNodeWithText(text(R.string.recovery_protection_verify)).assertIsNotEnabled()
        capture("confirmation")
        compose.onNode(hasSetTextAction()).performTextInput("wrong")
        compose.onNodeWithText(text(R.string.recovery_protection_verify)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.current_password_incorrect)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, success.get()); assertNull(f.prefs.getString(BiometricMdkBinding.ACTIVE, null))
        compose.onNode(hasSetTextAction()).performTextInput(BiometricWrapperRecoveryInstrumentedTest.PASSWORD)
        compose.onNodeWithText(text(R.string.recovery_protection_verify)).performClick()
        compose.waitUntil(5_000) { success.get() == 1 }
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(f.manager.unlockVaultWithBiometric())
        assertEquals("synthetic-ui-original", f.manager.decryptData(cipher))
        assertEquals(0, dismiss.get())
    }
    @Test fun largeTextCancelRetainsAllMaterial() {
        val before = f.prefs.all.toMap(); val success = AtomicInteger(); val dismiss = AtomicInteger()
        show(success, dismiss, large = true)
        compose.onNodeWithText(text(R.string.recovery_protection_verify)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed()
        capture("large-text")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(BiometricWrapperRecoveryInstrumentedTest.PASSWORD)
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(1, dismiss.get()); assertEquals(0, success.get()) }
        assertEquals(before, f.prefs.all)
    }
    @Test fun lockRemovesTypedPasswordAndPreventsConfirmation() {
        val before = f.prefs.all.toMap(); val success = AtomicInteger(); val dismiss = AtomicInteger()
        show(success, dismiss)
        compose.onNode(hasSetTextAction()).performTextInput(BiometricWrapperRecoveryInstrumentedTest.PASSWORD)
        SecurityManager.clearRuntimeUnlockCache()
        compose.waitUntil(5_000) { dismiss.get() == 1 }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(0, success.get()); assertEquals(before, f.prefs.all)
    }

    @Test fun settingsShowsVerifiedStatusAndOpensExistingBackupAtLargeText() {
        val config = android.content.res.Configuration(f.base.resources.configuration).apply {
            setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        }
        val localized = f.base.createConfigurationContext(config)
        val backup = AtomicInteger()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalResources provides localized.resources,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                LocalDensity provides Density(density.density, 1.5f),
            ) { MaterialTheme { RecoveryProtectionSetting(f.manager) { backup.incrementAndGet() } } }
        }
        compose.onNodeWithTag("recovery_protection_setting").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(localized.getString(R.string.recovery_material_available)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("settings-before-zh-large")
        compose.onNodeWithText(localized.getString(R.string.recovery_protection_verify)).performClick()
        capture("confirmation-zh-large")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(BiometricWrapperRecoveryInstrumentedTest.PASSWORD)
        compose.onNodeWithText(localized.getString(R.string.recovery_protection_verify)).performClick()
        compose.waitUntil(10_000) {
            f.manager.recoveryProtectionStatus().verifiedAtMillis != null &&
                compose.onAllNodesWithTag("recovery_protection_status").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("settings-verified-zh-large")
        compose.onNodeWithText(localized.getString(R.string.sync_backup_title)).performScrollTo().performClick()
        assertEquals(1, backup.get())
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}

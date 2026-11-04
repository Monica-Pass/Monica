package takagi.ru.monica.bitwarden

import android.util.Base64
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

@RunWith(AndroidJUnit4::class)
class BitwardenNeverLockSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val english get() = LocaleHelper.setLocale(context, Language.ENGLISH)
    private val password = "Synthetic master password 325!"

    @Test fun fullSettingsPageKeepsSyncAndSecurityReadable() = captureFullSettings(false, 1f)

    @Test fun fullSettingsPageKeepsSyncAndSecurityReadableAtLargeFontInDarkTheme() = captureFullSettings(true, 1.5f)

    private fun captureFullSettings(dark: Boolean, fontScale: Float) = fixture(dark, fontScale) { model, first, _ ->
        var unlocked: Boolean? = null
        compose.runOnIdle { model.unlock(first.id, password) { unlocked = it } }
        compose.waitUntil(20_000) { unlocked != null }
        assertEquals(true, unlocked)
        compose.runOnIdle { model.isAutoSyncEnabled = true; model.isNeverLockEnabled = true }
        val suffix = if (dark) "dark-large" else "light-normal"
        screenshot("full-settings-accounts-$suffix")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("bitwarden_sync_queue_entry"))
        // Place the sync section beneath the real toolbar for the visual review.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(model.vaults.value.size + 2)
        compose.onNodeWithTag("bitwarden_sync_queue_entry").assertIsDisplayed()
        screenshot("full-settings-sync-$suffix")
        compose.onNodeWithTag("bitwarden_never_lock").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(english.getString(R.string.legacy_ui_never_lock_warning)).performScrollTo().assertIsDisplayed()
        screenshot("full-settings-security-$suffix")
        compose.onNodeWithTag("bitwarden_never_lock").performClick()
        compose.runOnIdle { assertFalse(model.isNeverLockEnabled) }
        compose.onNodeWithText(english.getString(R.string.legacy_ui_never_lock_warning)).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("settingsScreenshots") != "true") return
        compose.waitForIdle()
        val dir = File(context.filesDir, "bitwarden-settings-325").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun failedConfirmationCannotEnablePreferenceAfterAnUnrelatedSuccessfulUnlock() = fixture { model, first, _ ->
        openConfirmation()
        compose.onNode(hasSetTextAction()).performTextInput("wrong synthetic password")
        compose.onNodeWithText(english.getString(R.string.unlock)).performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                model.unlockStateByVault.value[first.id] == BitwardenViewModel.UnlockState.Locked
        }
        // Await the actual repository result instead of merely observing the initial Locked value.
        compose.waitForIdle()
        var callback: Boolean? = null
        compose.runOnIdle { model.unlock(first.id, password) { callback = it } }
        compose.waitUntil(20_000) { callback != null }
        assertEquals(true, callback)
        assertFalse(model.isNeverLockEnabled)
        compose.onNodeWithTag("bitwarden_never_lock").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model.isNeverLockEnabled) }
    }

    @Test fun switchingAccountsDismissesAuthorizationAndDoesNotApplyItToAnotherAccount() = fixture { model, first, second ->
        openConfirmation()
        compose.runOnIdle { model.setActiveVault(second) }
        compose.waitUntil { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        var callback: Boolean? = null
        compose.runOnIdle { model.unlock(first.id, password) { callback = it } }
        compose.waitUntil(20_000) { callback != null }
        assertEquals(true, callback)
        assertFalse(model.isNeverLockEnabled)
        assertEquals(second.id, model.activeVault.value?.id)
    }

    @Test fun successfulExplicitConfirmationEnablesPreferenceForTheSelectedAccount() = fixture { model, first, _ ->
        openConfirmation()
        compose.onNode(hasSetTextAction()).performTextInput(password)
        compose.onNodeWithText(english.getString(R.string.unlock)).performClick()
        compose.waitUntil(20_000) { model.isNeverLockEnabled }
        assertTrue(model.isVaultUnlocked(first.id))
        compose.onNodeWithTag("bitwarden_never_lock").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(model.isNeverLockEnabled) }
    }

    private fun openConfirmation() {
        compose.onNodeWithTag("bitwarden_never_lock").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    private fun fixture(dark: Boolean = false, fontScale: Float = 1f,
        block: (BitwardenViewModel, BitwardenVault, BitwardenVault) -> Unit) {
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val previousNever = repository.isNeverLockEnabled
        val previousAuto = repository.isAutoSyncEnabled
        val store = ViewModelStore()
        val owned = mutableListOf<BitwardenVault>()
        try {
            repository.isNeverLockEnabled = false
            repository.isAutoSyncEnabled = false
            runBlocking {
                repeat(2) { index ->
                    val uuid = UUID.randomUUID().toString()
                    val email = "settings-$uuid@example.invalid"
                    val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, 1000)
                    fun encoded(value: ByteArray): String = Base64.encodeToString(
                        Base64.encodeToString(value, Base64.NO_WRAP).toByteArray(), Base64.NO_WRAP)
                    val vault = BitwardenVault(email = email, canonicalEmail = email, accountKey = "settings-$uuid", userId = uuid,
                        displayName = "Synthetic settings account $index", isLocked = true, syncEnabled = false,
                        encryptedAccessToken = Base64.encodeToString("synthetic-settings-token-$uuid".toByteArray(), Base64.NO_WRAP),
                        accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                        encryptedMasterKey = encoded(master), encryptedEncKey = encoded(ByteArray(32) { 7 }),
                        encryptedMacKey = encoded(ByteArray(32) { 11 }), kdfIterations = 1000)
                    master.fill(0)
                    owned += vault.copy(id = database.bitwardenVaultDao().insert(vault))
                }
            }
            val model = BitwardenViewModel(compose.activity.application)
            store.put("settings", model)
            compose.waitUntil(20_000) { model.vaults.value.any { it.id == owned[0].id } }
            compose.runOnIdle { model.setActiveVault(owned[0]) }
            val localized = english
            val configuration = Configuration(localized.resources.configuration).apply { this.fontScale = fontScale }
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, fontScale)) {
                    MonicaTheme(darkTheme = dark) { Surface { BitwardenSettingsScreen(model, {}, {}, {}, {}) } }
                }
            }
            block(model, owned[0], owned[1])
        } finally {
            compose.runOnIdle { store.clear() }
            repository.isNeverLockEnabled = false
            runBlocking {
                owned.forEach { vault ->
                    repository.lock(vault.id)
                    check(database.bitwardenVaultDao().getVaultById(vault.id)?.accountKey == vault.accountKey)
                    database.bitwardenVaultDao().deleteById(vault.id)
                }
            }
            repository.isNeverLockEnabled = previousNever
            repository.isAutoSyncEnabled = previousAuto
        }
    }
}

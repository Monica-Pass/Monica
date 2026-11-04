package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevice
import takagi.ru.monica.bitwarden.ui.*
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

@RunWith(AndroidJUnit4::class)
class BitwardenSettingsDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = LocaleHelper.setLocale(instrumentation.targetContext, Language.ENGLISH)
    private val vault = BitwardenVault(id = 325, email = "synthetic-long-account@example.invalid",
        serverUrl = "https://vault.example.invalid/team/passwords", displayName = "Personal test account")

    @Test fun expandedAccountActionsWrapAndInvokeTheCorrectCallbacks() {
        val actions = mutableListOf<String>()
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                VaultCard(vault, false, BitwardenViewModel.UnlockState.Unlocked, null,
                    { actions += "select" }, { actions += "lock" }, { actions += "unlock" },
                    { actions += "sync" }, { actions += "logout" }, { actions += "account" }, 2, { actions += "issues" })
            }
        }
        compose.onNodeWithText(vault.displayName!!).performClick()
        settleLayout()
        val ids = listOf(R.string.bitwarden_settings_select_account, R.string.legacy_ui_sync,
            R.string.bitwarden_account_security, R.string.legacy_ui_lock, R.string.legacy_ui_logout)
        ids.forEach { id ->
            val label = context.getString(id)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            assertNoOverflow(label)
            compose.onNodeWithText(label).performClick()
        }
        compose.onNodeWithText(context.getString(R.string.bitwarden_sync_issues_button, 2)).performScrollTo().performClick()
        assertEquals(listOf("select", "sync", "account", "lock", "logout", "issues"), actions)
        compose.onNodeWithText(vault.displayName!!).performScrollTo()
        screenshot("account-expanded-large")
        assertNoOverflow(vault.email)
        assertNoOverflow(vault.serverUrl)
    }

    @Test fun lockingAccountImmediatelyHidesFailureDetailsAndSyncAction() {
        var unlocked by mutableStateOf(true)
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                VaultCard(vault, true, if (unlocked) BitwardenViewModel.UnlockState.Unlocked else BitwardenViewModel.UnlockState.Locked,
                    null, {}, { unlocked = false }, {}, {}, {}, {}, 2, {})
            }
        }
        compose.onNodeWithText(vault.displayName!!).performClick()
        settleLayout()
        compose.onNodeWithText(context.getString(R.string.legacy_ui_lock)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.bitwarden_sync_issues_button, 2)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.legacy_ui_sync)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.unlock)).performScrollTo().assertIsDisplayed()
        screenshot("account-locked-large")
    }

    @Test fun emptyQueueEntryStillNavigatesAndSwitchesKeepTheirMeaning() {
        var auto by mutableStateOf(false)
        var wifi by mutableStateOf(true)
        var keep by mutableStateOf(false)
        var navigations = 0
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                SyncSettingsCard(auto, { auto = it }, wifi, { wifi = it }, keep, { keep = it },
                    onOpenSyncQueue = { navigations++ })
            }
        }
        compose.onNodeWithTag("bitwarden_sync_queue_entry").performClick()
        assertEquals(1, navigations)
        compose.onNodeWithText(context.getString(R.string.legacy_ui_sync_wifi_only)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.legacy_ui_auto_sync)).performScrollTo().performClick()
        assertTrue(auto)
        compose.onNodeWithText(context.getString(R.string.legacy_ui_sync_wifi_only)).performScrollTo().performClick()
        assertFalse(wifi)
        compose.onNodeWithTag("bitwarden_never_lock").performScrollTo().performClick()
        assertTrue(keep)
        assertNoOverflow(context.getString(R.string.legacy_ui_never_lock))
        assertNoOverflow(context.getString(R.string.legacy_ui_never_lock_hint))
        assertNoOverflow(context.getString(R.string.legacy_ui_never_lock_warning))
        screenshot("sync-security-large")
    }

    @Test fun accountDetailsKeepLongIdentityAndDeviceStateReadable() {
        var opens = 0
        show(dark = true) {
            BitwardenAccountContent(vault.displayName, vault.email, vault.serverUrl,
                listOf(BitwardenAccountDevice(id = "synthetic-device", name = "Synthetic Android device with a long label", isTrusted = true)),
                false, null, { opens++ }, Modifier.fillMaxSize())
        }
        compose.onNodeWithTag("bitwarden_account_web_settings").performScrollTo().performClick()
        assertEquals(1, opens)
        compose.onNodeWithTag("bitwarden_account_device_synthetic-device").performScrollTo().assertIsDisplayed()
        assertNoOverflow("Synthetic Android device with a long label")
        compose.onNodeWithText(vault.displayName!!).performScrollTo()
        screenshot("account-details-dark-large")
    }

    @Test fun textBoundsGuardRejectsActualSingleLineClipping() {
        val label = "Synthetic text that cannot fit in this narrow single line"
        show { androidx.compose.material3.Text(label, Modifier.width(80.dp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip) }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        assertTrue("The guard must reject real truncation", results.none(::textFits))
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        val localized = context
        val configuration = Configuration(localized.resources.configuration).apply { fontScale = 1.5f }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density, 1.5f)) {
                MonicaTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun settleLayout() {
        // Inspect the resting layout, after expansion and supporting-text recomposition.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun assertNoOverflow(label: String) {
        settleLayout()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Expected actual text layout", results.isNotEmpty())
        if (results.any { !textFits(it) }) screenshot("overflow-" + label.replace(' ', '-'))
        val measurements = results.joinToString { result ->
            "size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
                "constraints=${result.layoutInput.constraints}, lines=${result.lineCount}, " +
                "widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, " +
                "lineBounds=" + (0 until result.lineCount).joinToString { line ->
                    "${result.getLineLeft(line)}..${result.getLineRight(line)} / ${result.getLineBottom(line)}"
                }
        }
        assertTrue("Text clipped: $label; $measurements", results.all(::textFits))
    }

    private fun textFits(result: TextLayoutResult): Boolean =
        // Compose can retain the full paragraph constraint while sizing a short label to its text.
        // Inspect actual line bounds, ellipsis and complete character coverage instead of that width.
        !result.didOverflowHeight && result.lineCount > 0 &&
            result.getLineEnd(result.lineCount - 1, visibleEnd = false) == result.layoutInput.text.length &&
            (0 until result.lineCount).all { line ->
                !result.isLineEllipsized(line) && result.getLineLeft(line) >= -1f &&
                    result.getLineRight(line) <= result.size.width + 1f &&
                    result.getLineTop(line) >= -1f && result.getLineBottom(line) <= result.size.height + 1f
            }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("settingsScreenshots") != "true") return
        compose.waitForIdle()
        val dir = File(instrumentation.targetContext.filesDir, "bitwarden-settings-325").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

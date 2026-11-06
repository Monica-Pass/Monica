package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.ClearDataSheetContent
import takagi.ru.monica.ui.theme.MonicaTheme

class UnifiedSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var localized: android.content.Context
    private fun show(dark: Boolean = true, scale: Float = 1f, content: @Composable () -> Unit) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(config) }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(360.dp).fillMaxHeight().testTag("unified_frame")) { content() }
                }
            }
        }
    }
    private fun label(id: Int) = localized.getString(id)
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("unified_frame").captureToImage().asAndroidBitmap()
        File(context.filesDir, "unified-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun awaitDialog(tag: String, name: String) {
        try {
            compose.waitUntil(5_000) { compose.onNodeWithTag(tag).isDisplayed() }
        } finally {
            val roots = compose.onAllNodes(isRoot())
            File(context.filesDir, "unified-$name-tree.txt").writeText(
                roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(context.filesDir, "unified-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun databaseRoutesRemainDistinct() {
        val events = mutableListOf<String>()
        show {
            SyncBackupScreen({}, { events += "export" }, { events += "import" },
                { events += "webdav" }, { events += "onedrive" },
                { events += "keepass" }, { events += "mdbx" }, { events += "bitwarden" },
                onNavigateToLanSync = { events += "nearby" }, isPlusActivated = true)
        }
        compose.onNodeWithText("去重引擎").assertDoesNotExist()
        capture("database-dark")
        listOf(R.string.mdbx_ui_manager_entry_title to "mdbx", R.string.local_keepass_database to "keepass",
            R.string.webdav_backup to "webdav",
            R.string.onedrive_backup_title to "onedrive", R.string.sync_backup_bitwarden_sync_title to "bitwarden",
            R.string.lan_title to "nearby", R.string.export_data to "export", R.string.import_data to "import").forEach { (id, expected) ->
            compose.onNodeWithText(label(id)).performScrollTo().performClick()
            assertEquals(expected, events.last())
        }
        assertEquals(8, events.size)
        capture("database-bottom")
    }
    @Test fun unavailablePlusRouteStaysDisabledAtLargeText() {
        var opened = false
        show(dark = false, scale = 1.5f) { SyncBackupScreen({}, onNavigateToBitwarden = { opened = true }) }
        compose.onNodeWithText(label(R.string.sync_backup_bitwarden_sync_title)).performScrollTo().assertIsNotEnabled()
        assertFalse(opened)
        capture("database-light-large")
    }
    @Test fun extensionRowsToggleOnceAndKeepChoices() {
        var dedup by mutableStateOf(false)
        var toggles = 0
        var clipboard by mutableIntStateOf(0)
        show {
            ExtensionsScreen({}, isPlusActivated = true, smartDeduplicationEnabled = dedup,
                onSmartDeduplicationEnabledChange = { dedup = it; toggles++ },
                clipboardAutoClearSeconds = clipboard, onClipboardAutoClearSecondsChange = { clipboard = it })
        }
        capture("extensions-dark")
        compose.onNodeWithText(label(R.string.smart_deduplication)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dedup); assertEquals(1, toggles) }
        compose.onNodeWithText(label(R.string.clipboard_auto_clear_title)).performScrollTo().performClick()
        awaitDialog("extensions_clipboard_dialog", "clipboard-dialog")
        compose.onNodeWithTag("extensions_clipboard_close").assertIsDisplayed().performClick()
        assertEquals(0, clipboard)
    }
    @Test fun autofillSettingsRetainStrategyDialog() {
        show(scale = 1.5f) { AutofillSettingsScreen({}) }
        capture("autofill-dark-large")
        compose.onNodeWithText(label(R.string.autofill_domain_strategy_title)).performScrollTo().performClick()
        awaitDialog("autofill_strategy_dialog", "strategy-dialog")
        compose.onNodeWithText(takagi.ru.monica.autofill_ng.DomainMatchStrategy.getDisplayName(localized, takagi.ru.monica.autofill_ng.DomainMatchStrategy.REGEX)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("autofill_strategy_close").assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.autofill_blacklist_enable)).performScrollTo().assertIsDisplayed()
        capture("autofill-bottom")
    }
    @Test fun compactStatusDoesNotClaimDisabledAppIsOperational() {
        val status = takagi.ru.monica.autofill_ng.core.AutofillServiceChecker.ServiceStatus(
            true, true, false, true, emptyList(), listOf("Synthetic recovery instruction"))
        show { takagi.ru.monica.ui.components.AutofillStatusCard(status, {}, compact = true) }
        compose.onNodeWithText(label(R.string.autofill_status_all_functional)).assertDoesNotExist()
        compose.onNodeWithText("Synthetic recovery instruction").assertIsDisplayed()
    }

    @Test fun clearSelectionAndAuthenticationGatesStayVisible() {
        var selected by mutableStateOf(List(6) { true })
        var password by mutableStateOf("")
        var submitted: List<Boolean>? = null
        var canceled = false
        show(scale = 1.5f) {
            ClearDataSheetContent(selected, { index, value -> selected = selected.toMutableList().apply { set(index, value) } },
                password, { password = it }, true, { canceled = true }, { submitted = selected })
        }
        compose.onNodeWithTag("clear_data_confirm").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("clear_data_type_0").performScrollTo().performClick()
        compose.onNodeWithTag("clear_data_password").performScrollTo().performTextInput("synthetic")
        compose.onNodeWithTag("clear_data_confirm").assertIsDisplayed().assertIsEnabled()
        capture("clear-dark-large")
        compose.onNodeWithTag("clear_data_confirm").performClick()
        assertEquals(listOf(false, true, true, true, true, true), submitted)
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertTrue(canceled)
    }
    @Test fun clearWithNoSelectionCannotSubmitEvenWithoutPasswordVerification() {
        var submitted = false
        show(dark = false) { ClearDataSheetContent(List(6) { false }, { _, _ -> }, "", {}, false, {}, { submitted = true }) }
        compose.onNodeWithTag("clear_data_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("clear_data_password").assertDoesNotExist()
        capture("clear-light")
        assertFalse(submitted)
    }
}

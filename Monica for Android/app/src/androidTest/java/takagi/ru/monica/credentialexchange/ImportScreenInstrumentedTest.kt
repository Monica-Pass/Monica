package takagi.ru.monica.credentialexchange

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.FileOperationHelper

@RunWith(AndroidJUnit4::class)
class ImportScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemSourcePickerCancellationOrUnavailabilityNeverImportsData() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        var imports = 0
        val screenContext = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            compose.setContent {
                val activityContext = LocalContext.current
                val localizedContext = remember(activityContext) { ContextThemeWrapper(activityContext, 0).apply {
                    applyOverrideConfiguration(screenContext.resources.configuration)
                } }
                if (visible.value) CompositionLocalProvider(LocalContext provides localizedContext) {
                    MonicaTheme { ImportDataScreen(onNavigateBack = {}, onImport = { Result.success(0) },
                        onImportExchange = { imports++; fixture.model.importCredentialExchange(it, ImportDestination.Local) },
                        onImportAegis = { Result.success(0) }, onImportEncryptedAegis = { _, _ -> Result.success(0) },
                        onImportSteamMaFile = { Result.success(0) }, onImportZip = { _, _, _ -> Result.success(0) }) }
                }
            }
            val label = screenContext.getString(R.string.exchange_choose_app)
            compose.onNodeWithText(label).performClick()
            fun foreground() = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            // AndroidX launches an intermediate Activity before the Google selector. During that
            // transition the import Activity deliberately has no visible Compose hierarchy.
            fun returned() = runCatching {
                compose.onAllNodesWithText(label).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            var pickerPackage = ""
            compose.waitUntil(30_000) {
                pickerPackage = foreground()
                (pickerPackage.isNotEmpty() && pickerPackage != fixture.context.packageName) || returned()
            }
            if (pickerPackage.isNotEmpty() && pickerPackage != fixture.context.packageName) {
                screenshot(fixture, "import-system-picker.png", waitForCompose = false)
                automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
                automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
            }
            compose.waitUntil(30_000) { returned() }
            compose.onNodeWithText(label).assertIsDisplayed()
            assertEquals(0, imports)
            assertTrue(fixture.importedPasswords(ImportDestination.Local).isEmpty())
            assertTrue(fixture.importedKeys(ImportDestination.Local).isEmpty())
            val output = File(fixture.context.getExternalFilesDir(null), "credential-exchange-ui").apply { mkdirs() }
            File(output, "system-picker-result.txt").writeText(
                "Foreground after request: $pickerPackage\nImported credentials: $imports\nReturned to import screen: true\n")
            screenshot(fixture, "import-system-picker-return.png")
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun destinationFormatFileAndResultStayInOneUsableFlow() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        var selected by mutableStateOf(ImportDestination.Local)
        var navigatedBack = false
        val screenContext = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val target = fixture.keepass()
        try {
            compose.setContent {
                val activityContext = LocalContext.current
                val localizedContext = remember(activityContext) { ContextThemeWrapper(activityContext, 0).apply {
                    applyOverrideConfiguration(screenContext.resources.configuration)
                } }
                if (visible.value) CompositionLocalProvider(LocalContext provides localizedContext) {
                    val summary by fixture.model.lastImportSummary.collectAsState()
                    MonicaTheme {
                        ImportDataScreen(destination = selected, onDestinationChange = { selected = it },
                            importSummary = summary, onResetSummary = fixture.model::clearImportSummary,
                            onNavigateBack = { navigatedBack = true },
                            onImport = { fixture.model.importData(it, destination = selected) },
                            onImportChromeCsv = { fixture.model.importChromeCsv(it, selected) },
                            onImportAegis = { fixture.model.importAegisJson(it, selected) },
                            onImportEncryptedAegis = { uri, password -> fixture.model.importEncryptedAegisJson(uri, password, selected) },
                            onImportSteamMaFile = { Result.success(0) },
                            onImportZip = { uri, password, restoreConfig -> fixture.model.importZipBackup(uri, password, selected, restoreConfig) })
                    }
                }
            }
            fun text(id: Int) = screenContext.getString(id)
            compose.onNodeWithText(text(R.string.exchange_choose_app)).assertIsDisplayed()
            screenshot(fixture, "import-apps.png")
            compose.onNodeWithText(text(R.string.exchange_files)).performClick()
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("${fixture.prefix}-KDBX").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("${fixture.prefix}-KDBX").performClick()
            assertEquals(target, selected)
            compose.onNodeWithText(text(R.string.import_type_monica_backup_title)).performClick()
            compose.onNodeWithText(text(R.string.import_type_csv_data_title)).performClick()
            compose.onNodeWithText(text(R.string.import_type_csv_chrome_title)).performClick()
            screenshot(fixture, "import-files.png")
            val file = File(fixture.root, "source.csv").apply {
                writeText("name,url,username,password\n${fixture.prefix}-ui,https://example.invalid,alice,fixture-password\n")
            }
            // Deliver the Android document-picker result into the production callback.
            compose.runOnIdle { FileOperationHelper.handleImportResult(FileOperationHelper.REQUEST_CODE_IMPORT,
                Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.import_data_file_selected)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.start_import)).performClick()
            compose.waitUntil(30_000) { fixture.model.lastImportSummary.value != null }
            compose.onNodeWithText(text(R.string.exchange_import_done)).performScrollTo().assertIsDisplayed()
            assertFalse(navigatedBack)
            assertEquals(1, fixture.model.lastImportSummary.value!!.imported)
            assertEquals(target, selected)
            assertEquals("fixture-password", fixture.keepassEntries(target.databaseId).single().fields.getValue("Password").content)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text(R.string.start_import)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            screenshot(fixture, "import-result.png")
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun polishLargeTextKeepsDestinationAndPrimaryActionReachable() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        val screenContext = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag("pl")) })
        try {
            compose.setContent {
                val activityContext = LocalContext.current
                val localizedContext = remember(activityContext) { ContextThemeWrapper(activityContext, 0).apply {
                    applyOverrideConfiguration(screenContext.resources.configuration)
                } }
                if (visible.value) CompositionLocalProvider(LocalContext provides localizedContext,
                    LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    MonicaTheme { ImportDataScreen(onNavigateBack = {}, onImport = { Result.success(0) },
                        onImportAegis = { Result.success(0) }, onImportEncryptedAegis = { _, _ -> Result.success(0) },
                        onImportSteamMaFile = { Result.success(0) }, onImportZip = { _, _, _ -> Result.success(0) }) }
                }
            }
            compose.onNodeWithText(screenContext.getString(R.string.exchange_choose_app)).assertIsDisplayed()
            compose.onNodeWithText(screenContext.getString(R.string.exchange_save_to)).assertIsDisplayed()
            screenshot(fixture, "import-polish-large.png")
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            screenshot(fixture, "import-destinations-polish-large.png")
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    private fun screenshot(fixture: TransferFixture, name: String, waitForCompose: Boolean = true) {
        if (waitForCompose) compose.waitForIdle()
        val output = File(fixture.context.getExternalFilesDir(null), "credential-exchange-ui").apply { mkdirs() }
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}

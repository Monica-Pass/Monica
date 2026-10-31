package takagi.ru.monica.credentialexchange

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.transfer.*
import takagi.ru.monica.ui.screens.ExportDataScreen
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.FileOperationHelper
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper

@RunWith(AndroidJUnit4::class)
class DatabaseTransferUiTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(fixture: TransferFixture, name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(fixture.context.filesDir, "database-transfer-validation").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun zipPasswordRetryShowsProgressAndReleasesBusyStateBeforeSnackbarDismissal() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        val gate = CompletableDeferred<Unit>()
        val stage = mutableStateOf<TransferProgress?>(null)
        val target = fixture.mdbx()
        val settings = takagi.ru.monica.utils.SettingsManager(fixture.context)
        val originalSettings = settings.exportPageAdjustmentSettings()
        settings.updateHapticFeedbackEnabled(false)
        val localized = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
            // Match this rule's Activity locale: Dialog creates a separate Android window.
            setLocale(Locale.ENGLISH)
        })
        val plain = File(fixture.root, "ui.zip")
        val encrypted = File(fixture.root, "ui.enc.zip")
        ZipOutputStream(plain.outputStream()).use {
            it.putNextEntry(ZipEntry("monica_config/page_adjustment_settings.json"))
            it.write("""{"hapticFeedbackEnabled":true}""".toByteArray())
            it.closeEntry()
            it.putNextEntry(ZipEntry("passwords/password.json"))
            it.write(JSONObject().put("id", 1).put("title", "${fixture.prefix}-ui")
                .put("username", "alice").put("password", "Synthetic UI password").put("website", "https://example.invalid")
                .toString().toByteArray())
            it.closeEntry()
        }
        EncryptionHelper.encryptFile(plain, encrypted, "archive password", AppLocaleStringResolver(fixture.context)).getOrThrow()
        try {
            compose.setContent {
                val activity = LocalContext.current
                val context = remember { ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(localized.resources.configuration) } }
                if (visible.value) CompositionLocalProvider(LocalContext provides context) {
                    val progress by fixture.model.importProgress.collectAsState()
                    val summary by fixture.model.lastImportSummary.collectAsState()
                    MonicaTheme { ImportDataScreen(destination = target, onNavigateBack = {},
                        importProgress = stage.value ?: progress, importSummary = summary,
                        onImport = { Result.success(0) }, onImportAegis = { Result.success(0) },
                        onImportEncryptedAegis = { _, _ -> Result.success(0) }, onImportSteamMaFile = { Result.success(0) },
                        onImportZip = { uri, password, restoreConfig ->
                            if (password == "archive password") {
                                stage.value = TransferProgress(TransferPhase.PREPARING, 3, 10)
                                gate.await()
                                stage.value = null
                            }
                            fixture.model.importZipBackup(uri, password, target, restoreConfig)
                        }) }
                }
            }
            fun text(id: Int) = localized.getString(id)
            compose.onNodeWithText(text(R.string.exchange_files)).performClick()
            compose.runOnIdle { FileOperationHelper.handleImportResult(FileOperationHelper.REQUEST_CODE_IMPORT,
                Activity.RESULT_OK, Intent().setData(Uri.fromFile(encrypted))) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text(R.string.start_import)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.start_import)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.webdav_restore_action)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.zip_restore_config)).assertIsOff().performClick().assertIsOn()
            screenshot(fixture, "zip-restore-config.png")
            compose.onNodeWithText(text(R.string.webdav_restore_action)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.transfer_zip_password_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("wrong password")
            compose.onNodeWithText(text(R.string.confirm)).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(text(R.string.import_data_password_incorrect_retry)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.confirm)).assertIsEnabled()
            assertTrue(fixture.importedPasswords(target).isEmpty())
            assertFalse(settings.exportPageAdjustmentSettings().hapticFeedbackEnabled)
            screenshot(fixture, "zip-password-retry.png")
            compose.onNode(hasSetTextAction()).performTextReplacement("archive password")
            compose.onNodeWithText(text(R.string.confirm)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer-progress").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transfer-progress").assertIsDisplayed()
            screenshot(fixture, "import-progress.png")
            gate.complete(Unit)
            compose.waitUntil(40_000) { fixture.model.lastImportSummary.value != null }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text(R.string.start_import)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("transfer-progress").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.start_import)).assertIsEnabled()
            assertEquals(1, fixture.model.lastImportSummary.value!!.imported)
            assertTrue(settings.exportPageAdjustmentSettings().hapticFeedbackEnabled)
        } catch (error: Throwable) {
            runCatching { screenshot(fixture, "zip-flow-failure.png") }
            throw error
        } finally {
            gate.complete(Unit)
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            settings.importPageAdjustmentSettings(originalSettings)
            fixture.close()
        }
    }

    @Test fun exportOutlivesPageAndReentryShowsSelectedDatabase() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val source = fixture.mdbx()
        fixture.importer.importExchange(fixture.decoded(), source)
        val output = File(fixture.root, "ui-background.enc.zip")
        val localized = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                    options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
                }
            }
        }
        DatabaseExportJobs.dismiss()
        try {
            compose.setContent {
                val activity = LocalContext.current
                val context = remember { ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(localized.resources.configuration) } }
                if (visible.value) CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) {
                    MonicaTheme { ExportDataScreen(onNavigateBack = { visible.value = false },
                        onExportZip = { uri, preferences, password, target, progress ->
                            started.complete(Unit)
                            progress.report(TransferProgress(TransferPhase.PACKING, 3, 10))
                            gate.await()
                            fixture.model.exportZipBackup(uri, preferences, password, target, progress)
                        }, onExportKdbx = { _, _, _, _ -> Result.success("") },
                        onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
                        onExportSteamMaFile = { _, _, _, _ -> Result.success("") }) }
                }
            }
            fun text(id: Int) = localized.getString(id)
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("${fixture.prefix}-MDBX").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("${fixture.prefix}-MDBX").performClick()
            screenshot(fixture, "export-source.png")
            compose.onNodeWithText(text(R.string.backup_content_webdav_config)).performScrollTo().performClick()
            screenshot(fixture, "zip-export-config.png")
            compose.onNodeWithText(text(R.string.transfer_choose_location)).performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("export password")
            compose.onAllNodes(hasSetTextAction())[1].performTextInput("export password")
            compose.onNodeWithText(text(R.string.zip_backup_password_export)).performClick()
            compose.waitUntil(10_000) { started.isCompleted }
            compose.onNodeWithTag("transfer-progress").assertIsDisplayed()
            screenshot(fixture, "export-background.png")
            compose.onNodeWithText(text(R.string.transfer_background_continue)).performClick()
            compose.runOnIdle { assertFalse(visible.value) }
            gate.complete(Unit)
            val result = withTimeout(60_000) { DatabaseExportJobs.state.first { it?.status != ExportJobStatus.RUNNING }!! }
            assertEquals(result.message, ExportJobStatus.SUCCEEDED, result.status)
            assertTrue(output.length() > 0)
            val decoded = File(fixture.root, "ui-config-decoded.zip")
            EncryptionHelper.decryptFile(output, decoded, "export password", AppLocaleStringResolver(fixture.context)).getOrThrow()
            java.util.zip.ZipFile(decoded).use { archive ->
                assertNotNull(archive.getEntry("monica_config/page_adjustment_settings.json"))
            }
            compose.runOnUiThread { visible.value = true }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("${fixture.prefix}-MDBX", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("${fixture.prefix}-MDBX", useUnmergedTree = true).assertIsDisplayed()
            screenshot(fixture, "export-result.png")
        } catch (error: Throwable) {
            runCatching { screenshot(fixture, "export-flow-failure.png") }
            throw error
        } finally {
            gate.complete(Unit)
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            DatabaseExportJobs.dismiss()
            fixture.close()
        }
    }

    @Test fun polishLargeTextKeepsExportLocationButtonVisible() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        val localized = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag("pl")) })
        try {
            compose.setContent {
                val activity = LocalContext.current
                val context = remember { ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(localized.resources.configuration) } }
                val density = LocalDensity.current
                if (visible.value) CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(density.density, 1.5f)) {
                    MonicaTheme { ExportDataScreen(onNavigateBack = {}, onExportZip = { _, _, _, _, _ -> Result.success("") },
                        onExportKdbx = { _, _, _, _ -> Result.success("") }, onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
                        onExportSteamMaFile = { _, _, _, _ -> Result.success("") }) }
                }
            }
            compose.onNodeWithText(localized.getString(R.string.transfer_choose_location)).assertIsDisplayed().assertIsEnabled()
            screenshot(fixture, "export-polish-large-text.png")
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }
}

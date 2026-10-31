package takagi.ru.monica.credentialexchange

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.DataExportImportManager
import takagi.ru.monica.util.FileOperationHelper
import takagi.ru.monica.util.TotpDataResolver

@RunWith(AndroidJUnit4::class)
class BitwardenAuthenticatorImportDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val header = "folder,favorite,type,name,login_uri,login_totp"
    private fun csv(f: TransferFixture, invalid: Boolean = false): File {
        val issuer = f.prefix
        fun row(account: String, hotp: Boolean): String {
            val kind = if (hotp) "hotp" else "totp"
            val params = if (hotp) "&algorithm=SHA512&digits=8&period=60&counter=4294967296" else "&algorithm=SHA256&digits=8&period=60"
            return ",,1,$issuer,,otpauth://$kind/$issuer:$account?secret=JBSWY3DPEHPK3PXP&issuer=$issuer$params,$account,30,6"
        }
        return File(f.root, "auth.csv").apply {
            writeText("$header\n${row("alice", false)}\n${row("bob", true)}\n" +
                if (invalid) ",,1,Broken,,not-an-otp\n" else "")
        }
    }

    @Test fun importsIntoAllFourDestinationsPreservingOtpAndSkippingRepeatedRecords() = runBlocking {
        val f = TransferFixture()
        try {
            val source = Uri.fromFile(csv(f, invalid = true))
            for (target in listOf(ImportDestination.Local, f.keepass(), f.mdbx(), f.bitwarden())) {
                assertEquals(2, f.model.importBitwardenAuthenticatorCsv(source, target).getOrThrow())
                assertEquals(1, f.model.lastImportSummary.value!!.failed)
                val items = f.secureItems.getItemsByType(ItemType.TOTP).first()
                    .filter { target.contains(it) && it.title == f.prefix }
                assertEquals(2, items.size)
                val data = items.map { checkNotNull(TotpDataResolver.parseStoredItemData(it.itemData,
                    decryptIfNeeded = f.security::decryptDataIfMonicaCiphertext)) }.associateBy { it.accountName }
                assertEquals(setOf("alice", "bob"), data.keys)
                assertEquals("SHA256", data.getValue("alice").algorithm)
                assertEquals(OtpType.HOTP, data.getValue("bob").otpType)
                assertEquals(4294967296L, data.getValue("bob").counter)
                assertTrue(data.values.all { it.digits == 8 && it.period == 60 && it.boundPasswordId == null })
                assertTrue(f.importedPasswords(target).isEmpty())
                assertEquals(0, f.model.importBitwardenAuthenticatorCsv(source, target).getOrThrow())
                assertEquals(2, f.model.lastImportSummary.value!!.skipped)
            }
        } finally { f.close() }
    }

    @Test fun suppliedCsvParsesLocallyWithoutAddingItsSecretsToTheRepository() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("authenticatorSample")
        assumeTrue("Optional private sample is provided only for local verification", argument != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = DataExportImportManager(context).importBitwardenAuthenticatorCsv(Uri.fromFile(File(argument!!))).getOrThrow()
        assertEquals(2, report.items.size)
        assertEquals(0, report.rejectedCount)
        assertTrue(report.items.all { it.itemType == ItemType.TOTP.name })
    }

    @Test fun separateCsvEntryDispatchesAuthenticatorImportAndKeepsDestinationAndResult() = runBlocking {
        val f = TransferFixture()
        val visible = mutableStateOf(true)
        var passwordImports = 0
        var authenticatorImports = 0
        var selected by mutableStateOf(ImportDestination.Local)
        val target = f.keepass()
        val localized = f.context.createConfigurationContext(Configuration(f.context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        try {
            compose.setContent {
                val activity = LocalContext.current
                val context = remember(activity) { ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(localized.resources.configuration) } }
                if (visible.value) CompositionLocalProvider(LocalContext provides context) {
                    val summary by f.model.lastImportSummary.collectAsState()
                    MonicaTheme { ImportDataScreen(onNavigateBack = {}, destination = selected, onDestinationChange = { selected = it },
                        importSummary = summary, onResetSummary = f.model::clearImportSummary,
                        onImport = { passwordImports++; f.model.importData(it, destination = selected) },
                        onImportBitwardenCsv = { passwordImports++; f.model.importBitwardenCsv(it, selected) },
                        onImportBitwardenAuthenticatorCsv = { authenticatorImports++; f.model.importBitwardenAuthenticatorCsv(it, selected) },
                        onImportAegis = { Result.success(0) }, onImportEncryptedAegis = { _, _ -> Result.success(0) },
                        onImportSteamMaFile = { Result.success(0) }, onImportZip = { _, _, _ -> Result.success(0) }) }
                }
            }
            fun label(id: Int) = localized.getString(id)
            compose.onNodeWithText(label(R.string.exchange_files)).performClick()
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("${f.prefix}-KDBX").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("${f.prefix}-KDBX").performClick()
            assertEquals(target, selected)
            compose.onNodeWithText(label(R.string.import_type_monica_backup_title)).performClick()
            compose.onNodeWithText(label(R.string.import_type_csv_data_title)).performClick()
            compose.onNodeWithText(label(R.string.import_type_csv_bitwarden_title)).assertIsDisplayed()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                File(f.context.getExternalFilesDir(null), "bitwarden-auth-import.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            compose.onNodeWithText("Bitwarden Auth.").performClick()
            val source = csv(f)
            compose.runOnIdle { FileOperationHelper.handleImportResult(FileOperationHelper.REQUEST_CODE_IMPORT,
                Activity.RESULT_OK, Intent().setData(Uri.fromFile(source))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.import_data_file_selected)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label(R.string.start_import)).performClick()
            compose.waitUntil(30_000) { f.model.lastImportSummary.value != null }
            compose.onNodeWithText(label(R.string.exchange_import_done)).performScrollTo().assertIsDisplayed()
            assertEquals(0, passwordImports)
            assertEquals(1, authenticatorImports)
            assertEquals(2, f.model.lastImportSummary.value!!.imported)
            assertEquals(target, selected)
            assertTrue(f.importedPasswords(target).isEmpty())
        } finally {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            f.close()
        }
    }
}

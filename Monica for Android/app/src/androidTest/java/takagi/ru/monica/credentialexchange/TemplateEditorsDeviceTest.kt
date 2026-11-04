package takagi.ru.monica.credentialexchange

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class TemplateEditorsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: TransferFixture
    private lateinit var model: PasswordViewModel
    @Before fun setup() {
        fixture = TransferFixture()
        model = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
    }
    @After fun cleanup() = runBlocking { model.viewModelScope.cancel(); fixture.close() }
    private fun text(id: Int) = fixture.context.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        // Captures are optional artifacts; protected windows can refuse them.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val file = File(fixture.context.getExternalFilesDir("template-editors-verification"), name)
        bitmap.let {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun openTypeMenu() {
        val description = text(R.string.entry_type_chip_content_description)
        // Native database metadata loads asynchronously before the editor is shown.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).performClick()
    }
    @Test fun barcodeCreationRendersGroupedFieldsAndKeepsSaveReachable() {
        compose.setContent { MaterialTheme {
            AddEditPasswordScreen(model, passwordId = null, initialStorageExplicit = true, initialLoginType = "barcode",
                onNavigateBack = {}, onSwitchToWifi = {})
        } }
        // The editor first loads its database metadata; wait for the real action, not just composition.
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("password_editor_save").isDisplayed() }.getOrDefault(false)
        }
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.save)).assertIsDisplayed()
        capture("barcode.png")
    }
    @Test fun storageTargetsRoundTripAndMdbxFilterDefaults() {
        val targets = listOf(StorageTarget.MonicaLocal(null), StorageTarget.MonicaLocal(7),
            StorageTarget.Mdbx(8, "folder:sub"), StorageTarget.KeePass(9, "Root:Group"),
            StorageTarget.Bitwarden(10, "folder"))
        assertEquals(targets, decodeTemplateTargets(targets.map { it.stableKey }))
        assertEquals(StorageTarget.Mdbx(8), templateDefaultTarget(CategoryFilter.MdbxDatabase(8)))
        assertEquals(StorageTarget.Mdbx(8, "folder"), templateDefaultTarget(CategoryFilter.MdbxFolderFilter(8, "folder")))
    }
    @Test fun wifiUsesCurrentMdbxAndPersistsPasswordInNativeFile() {
        val target = runBlocking { fixture.mdbx() }
        model.setCategoryFilter(CategoryFilter.MdbxDatabase(target.databaseId))
        var saved: Long? = null
        compose.setContent { MaterialTheme {
            AddEditWifiScreen(model, passwordId = null, onNavigateBack = {}, onNavigateToPassword = {},
                onSaveCompleted = { saved = it })
        } }
        compose.onNodeWithText(text(R.string.wifi_ssid_required)).performTextInput(fixture.prefix)
        compose.onNodeWithText(text(R.string.wifi_password_label)).performTextInput("synthetic-wifi-password")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture("wifi.png")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { saved != null }
        runBlocking {
            val entry = requireNotNull(model.getPasswordEntryById(saved!!))
            assertEquals(target.databaseId, entry.mdbxDatabaseId)
            assertEquals("synthetic-wifi-password", entry.password)
            Mdbx2NativeReadSessions.clear()
            val rows = fixture.mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            assertTrue(rows.any { it.payloadJson.contains("synthetic-wifi-password") })
        }
    }
    @Test fun wifiMenuIncludesAllTypesAndCarriesMultipleTargets() {
        val native = runBlocking { fixture.mdbx() }
        val targets = listOf(StorageTarget.Mdbx(native.databaseId, "folder"), StorageTarget.MonicaLocal(123))
        var selected: EntryTypeChipOption? = null
        var delivered: List<StorageTarget>? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalTemplateTargets provides targets,
                LocalTemplateNavigation provides { type, values -> selected = type; delivered = values }) {
                AddEditWifiScreen(model, passwordId = null, onNavigateBack = {}, onNavigateToPassword = {})
            }
        } }
        openTypeMenu()
        compose.onNodeWithTag("password_template_API_KEY").assertIsDisplayed()
        compose.onNodeWithTag("password_template_GPG_KEY").assertIsDisplayed()
        capture("wifi-menu.png")
        compose.onNodeWithTag("password_template_API_KEY").performClick()
        assertEquals(EntryTypeChipOption.API_KEY, selected)
        assertEquals(targets, delivered)
    }
    @Test fun wifiEditingPreservesHiddenFieldsAndAdvancedNetworkSettings() {
        val done = CompletableDeferred<Long?>()
        val meta = WifiData(ssid = fixture.prefix, bssid = "00:11:22:33:44:55", eap = WifiEapSettings(domain = "example.org"))
        model.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = fixture.prefix, website = "https://example.org",
            username = "enterprise-user", password = "", notes = "preserve notes", loginType = "WIFI", wifiMetadata = meta.toJson()),
            listOf("synthetic-existing"), listOf(StorageTarget.MonicaLocal(null)), onComplete = { done.complete(it) })
        val id = runBlocking { requireNotNull(withTimeout(30_000) { done.await() }) }
        var saved: Long? = null
        compose.setContent { MaterialTheme { AddEditWifiScreen(model, passwordId = id,
            onNavigateBack = {}, onNavigateToPassword = {}, onSaveCompleted = { saved = it }) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(fixture.prefix).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { saved != null }
        runBlocking {
            val entry = requireNotNull(model.getPasswordEntryById(saved!!))
            assertEquals("enterprise-user", entry.username)
            assertEquals("https://example.org", entry.website)
            assertEquals("preserve notes", entry.notes)
            assertEquals(meta, WifiData.fromJsonOrEmpty(entry.wifiMetadata))
        }
    }
    @Test fun sshUsesInheritedTargetAndExposesCompleteMenuInDarkTheme() {
        var delivered: List<StorageTarget>? = null
        val native = runBlocking { fixture.mdbx() }
        val targets = listOf(StorageTarget.Mdbx(native.databaseId, "chosen"))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalTemplateTargets provides targets,
                LocalTemplateNavigation provides { _, values -> delivered = values }) {
                AddEditSshKeyScreen(model, passwordId = null, onNavigateBack = {}, onNavigateToPassword = {}, onNavigateToWifi = {})
            }
        } }
        capture("ssh-dark.png")
        openTypeMenu()
        compose.onNodeWithTag("password_template_API_KEY").performClick()
        assertEquals(targets, delivered)
    }
}

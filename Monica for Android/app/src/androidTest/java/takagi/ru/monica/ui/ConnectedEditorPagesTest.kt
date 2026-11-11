package takagi.ru.monica.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.ui.components.labelRes
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*

/** Full editor compositions; save/reopen uses only an in-memory repository in the isolated test app. */
class ConnectedEditorPagesTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var security: SecurityManager
    private lateinit var db: PasswordDatabase
    private lateinit var bank: BankCardViewModel
    private lateinit var document: DocumentViewModel
    private lateinit var passwords: PasswordViewModel
    private lateinit var mdbx: MdbxViewModel
    private var wasUnlocked = false

    @Before fun prepare() {
        require(context.packageName in setOf("takagi.ru.monica.groups327", "takagi.ru.monica.groups327fd")) {
            "Full editor fixture must only run in this task's isolated application"
        }
        runBlocking { assertTrue(takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context) is
            takagi.ru.monica.security.SecureStartupResult.Ready) }
        security = SecurityManager(context)
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val strings = AppLocaleStringResolver(context)
        val items = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        compose.runOnIdle {
            bank = BankCardViewModel(items, strings = strings, sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
            document = DocumentViewModel(items, strings = strings, sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
            passwords = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
                customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = strings)
            mdbx = MdbxViewModel(context.applicationContext as Application, db.localMdbxDatabaseDao(), db.mdbxRemoteSourceDao(),
                db.passwordEntryDao(), db.secureItemDao(), db.passkeyDao(), db.attachmentDao(), db.customFieldDao(), security,
                backgroundOnly = true)
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CommonInfoTestActivity.content = {} }
        compose.waitForIdle()
        if (::bank.isInitialized) bank.viewModelScope.cancel()
        if (::document.isInitialized) document.viewModelScope.cancel()
        if (::passwords.isInitialized) passwords.viewModelScope.cancel()
        if (::mdbx.isInitialized) mdbx.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
        if (::security.isInitialized && !wasUnlocked) SessionManager.markLocked()
    }

    @Test fun authenticatorAccountGroupKeepsEditsThroughImportCancelAndSave() {
        var saved: TotpData? = null
        var scanned = 0
        show {
            AddEditTotpScreen(null, null, "Synthetic authenticator", "", initialStorageExplicit = true,
                passwordViewModel = passwords,
                onSave = { _, _, data, _, _, done -> saved = data; done(true) },
                onBatchImport = { _, _, _ -> }, onNavigateBack = {}, onScanQrCode = { scanned++ })
        }
        compose.onNodeWithTag("item_editor_secret").performTextReplacement("JBSWY3DPEHPK3PXP")
        inputInList("totp_item_editor", R.string.issuer, "Synthetic issuer")
        inputInList("totp_item_editor", R.string.account_name, "fixture@example.invalid")
        closeKeyboard()
        scrollText("totp_item_editor", R.string.issuer)
        capture("authenticator-account")
        scrollText("totp_item_editor", R.string.scan_qr_code)
        compose.onNodeWithText(text(R.string.scan_qr_code)).performClick()
        compose.runOnIdle { assertEquals(1, scanned) }
        compose.onNodeWithText(text(R.string.totp_import_uri_action)).performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        inputField(R.string.issuer).performScrollTo().assertTextContains("Synthetic issuer")
        compose.onNodeWithContentDescription(text(R.string.save)).performClick()
        compose.waitUntil(15000) { saved != null }
        assertEquals("Synthetic issuer", saved!!.issuer)
        assertEquals("fixture@example.invalid", saved!!.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", saved!!.secret)
    }

    @Test fun bankBasicAndPaymentRowsKeepValuesAfterSaveAndReopen() {
        var saved = false
        show { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        compose.onNodeWithTag("item_editor_title").performTextReplacement("Synthetic bank")
        inputInList("bank_item_editor", R.string.bank_name, "Example Bank")
        inputTagInList("bank_item_editor", "bank_branch", "Synthetic branch")
        inputTagInList("bank_item_editor", "entry_payment_number", "4111111111111111")
        inputTagInList("bank_item_editor", "entry_payment_holder", "Synthetic Holder")
        closeKeyboard()
        scrollText("bank_item_editor", R.string.bank_name)
        capture("bank-basic")
        scrollTag("bank_item_editor", "entry_payment_cvv")
        capture("bank-payment")
        compose.onNodeWithContentDescription(text(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = runBlocking { db.secureItemDao().getAllItems().first().single() }
        val data = requireNotNull(bank.parseCardData(item.itemData))
        assertEquals("4111111111111111", data.cardNumber)
        assertEquals("Synthetic Holder", data.cardholderName)
        assertEquals("Synthetic branch", data.bankBranch)
        show {}
        show { AddEditBankCardScreen(bank, cardId = item.id, onNavigateBack = {}) }
        scrollTag("bank_item_editor", "bank_branch")
        compose.onNodeWithTag("bank_branch").assertTextContains("Synthetic branch")
        capture("bank-reopened")
    }

    @Test fun documentTypeAndConnectedIdentityRowsKeepValuesAfterSaveAndReopen() {
        var saved = false
        show { AddEditDocumentScreen(document, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        compose.onNodeWithTag("item_editor_title").performTextReplacement("Synthetic document")
        scrollText("document_item_editor", R.string.document_type)
        compose.onNodeWithText(text(R.string.document_type)).performClick()
        compose.onNodeWithText(text(R.string.passport)).performClick()
        inputInList("document_item_editor", R.string.document_number_required, "SYNTHETIC-327")
        inputInList("document_item_editor", R.string.full_name, "Synthetic Holder")
        closeKeyboard()
        scrollText("document_item_editor", R.string.document_type)
        capture("document-basic")
        compose.onNodeWithTag("document_editor_save").performClick()
        compose.waitUntil(15000) { saved }
        val item = runBlocking { db.secureItemDao().getAllItems().first().single() }
        val data = requireNotNull(document.parseDocumentData(item.itemData))
        assertEquals(DocumentType.PASSPORT, data.documentType)
        assertEquals("SYNTHETIC-327", data.documentNumber)
        assertEquals("Synthetic Holder", data.fullName)
        show {}
        show { AddEditDocumentScreen(document, documentId = item.id, onNavigateBack = {}) }
        scrollText("document_item_editor", R.string.full_name)
        inputField(R.string.full_name).assertTextContains("Synthetic Holder")
        capture("document-reopened")
    }

    @Test fun apiTokenRowsKeepUnsavedDraftWhenBackIsCancelled() {
        var left = false
        show { AddEditApiTokenScreen(mdbx, onNavigateBack = { left = true }, onSaved = {},
            onSwitchType = { _, _, _ -> }, onManageDatabases = {}) }
        compose.onNodeWithTag("api_token_name").performTextReplacement("Synthetic API token")
        compose.onNodeWithTag("template_field_token").performScrollTo().performTextReplacement("synthetic-token")
        closeKeyboard()
        compose.onNodeWithTag("template_field_provider").performScrollTo()
        capture("api-token-page")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.api_token_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithTag("template_field_token").performScrollTo().assertTextContains("synthetic-token")
        compose.onNodeWithTag("api_token_save").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(left) }
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.api_token_discard)).performClick()
        compose.runOnIdle { assertTrue(left) }
        // There is deliberately no MDBX database in this visual fixture and no attempted write.
        assertEquals(0, runBlocking { db.localMdbxDatabaseDao().getAllDatabases().first().size })
    }

    @Test fun wifiGroupAndSecurityMenuKeepDraftUntilSaved() {
        var savedId: Long? = null
        show { AddEditPasswordScreen(passwords, passwordId = null, initialLoginType = "WIFI",
            initialStorageExplicit = true, onSaveCompleted = { savedId = it }, onNavigateBack = {}) }
        compose.onNodeWithTag("password_editor_title").performTextReplacement("Synthetic network")
        compose.onNodeWithTag("template_field_password").performScrollTo().performTextReplacement("  synthetic wifi  ")
        closeKeyboard()
        compose.onNodeWithTag("wifi_security").performScrollTo().performClick()
        compose.onNodeWithText(text(WifiSecurity.WPA3.labelRes())).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("template_field_password").assertTextContains("  synthetic wifi  ")
        capture("wifi-page")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20000) { savedId != null }
        val saved = runBlocking { passwords.getPasswordEntryById(requireNotNull(savedId))!! }
        assertEquals("Synthetic network", WifiEntryDetails.from(saved).ssid)
        assertEquals("  synthetic wifi  ", saved.password)
    }

    @Test fun ordinaryPasswordGroupStillAcceptsAndSavesTheOriginalFields() {
        var savedId: Long? = null
        var large by mutableStateOf(false)
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                AddEditPasswordScreen(passwords, passwordId = null, initialStorageExplicit = true,
                    initialDraft = AddEditPasswordInitialDraft(title = "Synthetic password"),
                    onSaveCompleted = { savedId = it }, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("password_editor_value_0").performScrollTo().performTextReplacement("  synthetic password  ")
        compose.onNodeWithTag("password_editor_value_0").assertTextContains("  synthetic password  ")
        closeKeyboard()
        capture("password-reference")
        compose.runOnIdle { large = true }
        compose.onNodeWithTag("password_editor_value_0").performScrollTo().assertTextContains("  synthetic password  ")
        capture("password-large-reference")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20000) { savedId != null }
        // The existing ordinary-password save path trims surrounding whitespace; Wi-Fi's
        // separate save path preserves it. Grouping must retain both established behaviors.
        assertEquals("synthetic password", runBlocking { passwords.getPasswordEntryById(requireNotNull(savedId))!!.password })
    }

    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = {
                CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme { Box(Modifier.width(360.dp).fillMaxHeight()) { content() } }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun text(id: Int) = context.getString(id)
    private fun inputField(id: Int) = compose.onNode(hasText(text(id)) and hasSetTextAction())
    private fun scrollTag(root: String, tag: String) {
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag(tag))
    }
    private fun scrollText(root: String, id: Int) {
        compose.onNodeWithTag(root).performScrollToNode(hasText(text(id)))
    }
    private fun inputInList(root: String, id: Int, value: String) {
        scrollText(root, id)
        inputField(id).performTextReplacement(value)
    }
    private fun inputTagInList(root: String, tag: String, value: String) {
        scrollTag(root, tag)
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }
    private fun closeKeyboard() { androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val directory = File(context.filesDir, "connected-editor-pages-327")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

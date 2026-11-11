package takagi.ru.monica.suggestions

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.repository.CommonFieldSuggestionSource
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditBillingAddressScreen
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.data.model.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.utils.PasswordWebsiteCodec

class CommonInfoSuggestionUiTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val security by lazy { SecurityManager(context) }
    private var wasUnlocked = false
    private val examples = mapOf(CommonSuggestionField.BANK_NAME to "Example Bank", CommonSuggestionField.BRANCH_CODE to "00123",
        CommonSuggestionField.CUSTOMER_SERVICE_PHONE to "400-000-0000", CommonSuggestionField.CREDENTIAL_LABEL to "Example Work",
        CommonSuggestionField.WEBSITE to "https://example.invalid/Account", CommonSuggestionField.ISSUED_BY to "Example Authority",
        CommonSuggestionField.FULL_NAME to "Example Name", CommonSuggestionField.FIRST_NAME to "Example First",
        CommonSuggestionField.MIDDLE_NAME to "Example Middle", CommonSuggestionField.LAST_NAME to "Example Last",
        CommonSuggestionField.STREET to "Example Street", CommonSuggestionField.APARTMENT to "Example Unit",
        CommonSuggestionField.ADDRESS_LINE_3 to "Example Third", CommonSuggestionField.CITY to "Example City",
        CommonSuggestionField.REGION to "Example Region", CommonSuggestionField.POSTAL_CODE to "00123",
        CommonSuggestionField.COUNTRY to "Example Country")
    private val source = CommonFieldSuggestionSource { field -> flowOf(CommonFieldSuggestionIndex(listOf(examples.getValue(field)), field)) }

    @Before fun unlock() { wasUnlocked = SessionManager.isUnlocked.value; SessionManager.markUnlocked() }
    @After fun reset() {
        show { }
        if (!wasUnlocked) SessionManager.markLocked()
    }
    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = {
                CompositionLocalProvider(LocalCommonFieldSuggestionSource provides source, LocalUiSecurityManager provides security) {
                    MaterialTheme { content() }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun waitSuggestion(field: CommonSuggestionField, hasSaveButton: Boolean = false) {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("common_suggestion_${field.key}_0").fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithTag("common_suggestion_${field.key}_0")
        if (!node.isDisplayed()) node.performScrollTo()
        compose.waitUntil(15000) {
            val root = compose.activity.window.decorView
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            val imeVisible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            val visible = android.graphics.Rect().also { root.getWindowVisibleDisplayFrame(it) }
            val bounds = node.fetchSemanticsNode().boundsInWindow
            val clearance = if (hasSaveButton) 80f * context.resources.displayMetrics.density else 0f
            imeVisible && bounds.top >= visible.top && bounds.bottom <= visible.bottom - clearance
        }
        node.assertIsDisplayed()
    }
    private fun capture(name: String) {
        val file = File(context.filesDir, "common-info-screens/$name.png").apply { parentFile?.mkdirs() }
        compose.waitForIdle()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun allSupportedFieldsFillOnlyOnTapAndKeepTypingAtSmallWidthAndLargeFont() {
        var current by mutableStateOf(CommonSuggestionField.BANK_NAME)
        var text by mutableStateOf("")
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                Column(Modifier.width(320.dp).fillMaxHeight().imePadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    key(current) { SuggestedOutlinedTextField(text, { text = it }, current,
                        modifier = Modifier.fillMaxWidth().testTag("input"), label = { Text(current.key) }) }
                    OutlinedTextField("Untouched", {}, modifier = Modifier.fillMaxWidth().testTag("other"))
                }
            }
        }
        for ((field, value) in examples) {
            compose.runOnIdle { current = field; text = "" }
            val query = value.take(3)
            compose.onNodeWithTag("input").performScrollTo().performClick().performTextInput(query)
            waitSuggestion(field)
            assertEquals(query, text)
            if (field == CommonSuggestionField.WEBSITE) capture("url-small-large-text")
            compose.onNodeWithTag("common_suggestion_${field.key}_0").performClick()
            compose.waitForIdle()
            assertEquals(value, text)
            compose.onNodeWithTag("common_suggestions_${field.key}").assertDoesNotExist()
            compose.onNodeWithTag("other").assertTextEquals("Untouched")
            compose.onNodeWithTag("input").performTextInput("x")
            assertEquals(value + "x", text)
            compose.onNodeWithTag("input").performTextReplacement(query)
            waitSuggestion(field)
            compose.onNodeWithTag("other").performScrollTo().performClick()
            compose.onNodeWithTag("common_suggestions_${field.key}").assertDoesNotExist()
        }
    }

    @Test fun lockingSessionHidesSuggestionsAndRecreationCanResumeTyping() {
        show { Column(Modifier.fillMaxSize().padding(12.dp)) {
            var value by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
            SuggestedOutlinedTextField(value, { value = it }, CommonSuggestionField.BANK_NAME, modifier = Modifier.testTag("input"))
        } }
        compose.onNodeWithTag("input").performClick().performTextInput("Exa")
        waitSuggestion(CommonSuggestionField.BANK_NAME)
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("common_suggestions_bankName").assertDoesNotExist()
        compose.runOnIdle { SessionManager.markUnlocked() }
        waitSuggestion(CommonSuggestionField.BANK_NAME)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("input").performClick().performTextReplacement("Exam")
        waitSuggestion(CommonSuggestionField.BANK_NAME)
        compose.onNodeWithTag("common_suggestion_bankName_0").performClick()
        compose.onNodeWithTag("input").assertTextContains("Example Bank")
    }

    @Test fun bankAndDocumentEditorsUseSuggestionsInTheirActualFields() {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repository = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        val bank = BankCardViewModel(repository, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        val document = DocumentViewModel(repository, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            show { AddEditBankCardScreen(bank, onNavigateBack = {}) }
            compose.onNodeWithTag("bank_item_editor").performScrollToNode(hasText(context.getString(R.string.bank_name)) and hasSetTextAction())
            compose.onNode(hasText(context.getString(R.string.bank_name)) and hasSetTextAction()).performClick().performTextInput("Exa")
            waitSuggestion(CommonSuggestionField.BANK_NAME, hasSaveButton = true)
            capture("bank-editor")
            compose.onNodeWithTag("common_suggestion_bankName_0").performClick()
            compose.onNode(hasText(context.getString(R.string.bank_name)) and hasSetTextAction()).assertTextContains("Example Bank")
            show { AddEditDocumentScreen(document, onNavigateBack = {}) }
            compose.onNodeWithTag("document_item_editor").performScrollToNode(hasText(context.getString(R.string.issuing_authority)) and hasSetTextAction())
            compose.onNode(hasText(context.getString(R.string.issuing_authority)) and hasSetTextAction()).performClick().performTextInput("Exa")
            waitSuggestion(CommonSuggestionField.ISSUED_BY, hasSaveButton = true)
            capture("document-editor")
            compose.onNodeWithTag("common_suggestion_issuedBy_0").performClick()
            compose.onNode(hasText(context.getString(R.string.issuing_authority)) and hasSetTextAction()).assertTextContains("Example Authority")
        } finally {
            show { }; bank.viewModelScope.cancel(); document.viewModelScope.cancel(); db.close()
        }
    }

    @Test fun credentialLabelAndOptionalBankFieldsUseSuggestionsWithoutChangingCredentials() {
        var group by mutableStateOf(ProjectCredentialGroup.Group(username = "Unchanged username"))
        var values by mutableStateOf(mapOf("branchCode" to "00", "customerServicePhone" to "400"))
        show { Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
            ProjectCredentialEditor(group, { group = it }, {}, {}, AppSettings())
            EntryOptionalFields(EntrySupplementalSpecs.payment.filter { it.key in values }, values, { spec, value -> values = values + (spec.key to value) })
        } }
        compose.onAllNodesWithContentDescription(context.getString(R.string.more_options)).onFirst().performClick()
        compose.onNodeWithText(context.getString(R.string.advanced_options)).performClick()
        compose.onNode(hasText(context.getString(R.string.project_credential_label)) and hasSetTextAction()).performClick().performTextInput("Exam")
        waitSuggestion(CommonSuggestionField.CREDENTIAL_LABEL)
        compose.onNodeWithTag("common_suggestion_credentialLabel_0").performClick()
        assertEquals("Example Work", group.label)
        assertEquals("Unchanged username", group.username)
        for (field in listOf(CommonSuggestionField.BRANCH_CODE, CommonSuggestionField.CUSTOMER_SERVICE_PHONE)) {
            compose.onNodeWithTag("entry_extra_${field.key}").performScrollTo().performClick()
            waitSuggestion(field)
            compose.onNodeWithTag("common_suggestion_${field.key}_0").performClick()
            assertEquals(examples[field], values[field.key])
        }
        capture("optional-fields")
    }

    @Test fun passwordEditorSavesSelectedUrlAndPreservesOtherUrlsAndCredentials() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
        val first = "https://old.invalid"
        val second = "https://preserved.invalid"
        val id = db.passwordEntryDao().insert(PasswordEntry(title = "Synthetic URLs", username = "preserved-user",
            password = security.encryptData("preserved-password"), website = PasswordWebsiteCodec.encode(listOf(first, second))))
        var saved by mutableStateOf(false)
        try {
            show { AddEditPasswordScreen(model, passwordId = id, initialStorageExplicit = true,
                onSaveCompleted = { saved = true }, onNavigateBack = {}) }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasText(first) and hasSetTextAction())
            compose.onNode(hasText(first) and hasSetTextAction()).performClick().performTextReplacement("https://exa")
            waitSuggestion(CommonSuggestionField.WEBSITE, hasSaveButton = true)
            capture("password-urls")
            compose.onNodeWithTag("common_suggestion_website_0").performClick()
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(15000) { saved }
            val entry = db.passwordEntryDao().getAllPasswordEntriesSync().single()
            assertEquals(listOf(examples.getValue(CommonSuggestionField.WEBSITE), second), PasswordWebsiteCodec.parse(entry.website))
            assertEquals("preserved-user", entry.username)
            assertEquals("preserved-password", security.decryptDataIfMonicaCiphertext(entry.password))
        } finally { show { }; model.viewModelScope.cancel(); db.close() }
    }

    @Test fun holderAndDocumentNamesUseTheSameInlineSuggestions() {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repository = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        val bank = BankCardViewModel(repository, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        val document = DocumentViewModel(repository, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            show { AddEditBankCardScreen(bank, onNavigateBack = {}) }
            compose.onNodeWithTag("bank_item_editor").performScrollToNode(hasTestTag("entry_payment_holder"))
            compose.onNodeWithTag("entry_payment_holder").performClick().performTextInput("Exam")
            waitSuggestion(CommonSuggestionField.FULL_NAME, hasSaveButton = true)
            capture("holder-name")
            compose.onNodeWithTag("common_suggestion_fullName_0").performClick()
            compose.onNodeWithTag("entry_payment_holder").assertTextContains("Example Name")
            val draft = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.DOCUMENT, title = "Synthetic",
                itemData = CardWalletDataCodec.encodeDocumentData(DocumentData(DocumentType.PASSPORT, "unchanged-id", "Before",
                    firstName = "PreservedFirst", middleName = "PreservedMiddle", lastName = "PreservedLast"))))
            var saved: takagi.ru.monica.attachments.EmbeddedWalletEditorResult? = null
            show { AddEditDocumentScreen(document, onNavigateBack = {}, embeddedDraft = draft, onEmbeddedSave = { saved = it }) }
            val nameField = hasText(context.getString(R.string.full_name)) and hasSetTextAction()
            compose.onNodeWithTag("embedded_document_editor").performScrollToNode(nameField)
            compose.onNode(nameField).performClick().performTextReplacement("Exam")
            waitSuggestion(CommonSuggestionField.FULL_NAME, hasSaveButton = true)
            compose.onNodeWithTag("common_suggestion_fullName_0").performClick()
            capture("document-name")
            compose.onNodeWithTag("document_editor_save").performClick()
            compose.waitUntil(15000) { saved != null }
            val data = CardWalletDataCodec.parseDocumentData(saved!!.snapshot.itemData.toString())!!
            assertEquals("Example Name", data.fullName)
            assertEquals("PreservedFirst", data.firstName)
            assertEquals("PreservedMiddle", data.middleName)
            assertEquals("PreservedLast", data.lastName)
            assertEquals("unchanged-id", data.documentNumber)
        } finally { show {}; bank.viewModelScope.cancel(); document.viewModelScope.cancel(); db.close() }
    }

    @Test fun billingNameAndStreetPersistWithoutChangingOtherAddressParts() {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repository = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        val model = BillingAddressViewModel(repository, security)
        val initial = BillingAddressData(fullName = "Before", streetAddress = "Old Street", city = "Unchanged City", postalCode = "00100", company = "Unchanged Company")
        val draft = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Synthetic Billing",
            itemData = CardWalletDataCodec.encodeBillingAddressData(initial)))
        var saved: takagi.ru.monica.attachments.EmbeddedWalletEditorResult? = null
        try {
            show { AddEditBillingAddressScreen(model, onNavigateBack = {}, embeddedDraft = draft, onEmbeddedSave = { saved = it }) }
            for ((label, field) in listOf(R.string.full_name to CommonSuggestionField.FULL_NAME, R.string.street_address to CommonSuggestionField.STREET)) {
                val matcher = hasText(context.getString(label)) and hasSetTextAction()
                compose.onNodeWithTag("billing_item_editor").performScrollToNode(matcher)
                compose.onNode(matcher).performClick().performTextReplacement("Exam")
                waitSuggestion(field, hasSaveButton = true)
                capture("billing-${field.key}")
                compose.onNodeWithTag("common_suggestion_${field.key}_0").performClick()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
            compose.waitUntil(15000) { saved != null }
            val data = CardWalletDataCodec.parseBillingAddressData(saved!!.snapshot.itemData.toString())!!
            assertEquals(initial.copy(fullName = "Example Name", streetAddress = "Example Street"), data)
            val reopened = saved!!.snapshot
            show {}
            show { AddEditBillingAddressScreen(model, onNavigateBack = {}, embeddedDraft = reopened, onEmbeddedSave = {}) }
            compose.onNodeWithTag("billing_item_editor").performScrollToNode(hasText("Example Name") and hasSetTextAction())
            compose.onNode(hasText("Example Name") and hasSetTextAction()).assertExists()
        } finally { show {}; model.viewModelScope.cancel(); db.close() }
    }
}

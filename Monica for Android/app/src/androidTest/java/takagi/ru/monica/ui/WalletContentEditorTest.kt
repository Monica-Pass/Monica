package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditBillingAddressScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel

class WalletContentEditorTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val security by lazy { SecurityManager(context) }
    private lateinit var db: PasswordDatabase
    private lateinit var bank: BankCardViewModel
    private lateinit var document: DocumentViewModel
    private lateinit var billing: BillingAddressViewModel
    private var wasUnlocked = false

    @Before fun prepare() {
        runBlocking {
            assertTrue("Standalone UI fixture requires prepared secure storage",
                takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context) is
                    takagi.ru.monica.security.SecureStartupResult.Ready)
        }
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repo = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        compose.runOnIdle {
            bank = BankCardViewModel(repo, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
            document = DocumentViewModel(repo, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
            billing = BillingAddressViewModel(repo)
        }
    }
    @After fun cleanup() {
        show { }
        bank.viewModelScope.cancel(); document.viewModelScope.cancel(); billing.viewModelScope.cancel(); db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }
    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = {
                CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme { content() } }
            }
        }
        compose.waitForIdle()
    }
    private fun scroll(root: String, tag: String) {
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag(tag))
    }
    private fun add(root: String, key: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        scroll(root, "item_editor_add_content")
        compose.onNodeWithTag("item_editor_add_content").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("item_editor_choose_$key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item_editor_choose_$key").performScrollTo().performClick()
        compose.onNodeWithTag("wallet_detail_${if (key.startsWith("custom_")) "custom" else key}").assertIsDisplayed()
    }
    private fun closeEditor() { compose.onNodeWithTag("wallet_detail_done").performClick() }
    private fun waitForEditorKeyboard() {
        compose.waitUntil(10000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                view.hasWindowFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idleness does not wait for the Android dialog window's enter animation.
        android.os.SystemClock.sleep(400)
        val file = File(context.filesDir, "wallet-content-screens/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun newBankCardCanDragContentsAndReopenWithOrderAndSecretsPreserved() = runBlocking {
        var saved by mutableStateOf(false)
        show { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        scroll("bank_item_editor", "entry_payment_number")
        compose.onNodeWithTag("entry_payment_number").performTextInput("4111111111111111")
        add("bank_item_editor", "notes")
        compose.onNode(hasText(context.getString(R.string.notes)) and hasSetTextAction()).performTextInput("Synthetic note")
        closeEditor()
        add("bank_item_editor", "extended")
        compose.onNodeWithTag("entry_extra_add").performClick()
        compose.onNodeWithTag("entry_extra_choose_pin").performScrollTo().performClick()
        compose.onNodeWithTag("entry_extra_pin").performTextInput("0123")
        closeEditor()
        add("bank_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performTextInput("Private field")
        compose.onNodeWithTag("wallet_custom_value_0").performTextInput("Synthetic secret")
        closeEditor()
        compose.onNodeWithTag("bank_item_editor").performScrollToIndex(1)
        val first = compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithTag("wallet_content_custom").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, last.height, 1f)
        assertEquals(first.width, other.width, 1f)
        compose.onNodeWithTag("wallet_content_notes").performTouchInput {
            down(center); advanceEventTime(650)
            moveTo(center + Offset(0f, first.center.y - last.center.y), delayMillis = 550)
            up()
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot.top)
        capture("bank-reordered")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = db.secureItemDao().getAllItems().first().single()
        val data = requireNotNull(bank.parseCardData(item.itemData))
        assertEquals("notes", data.editorSectionOrder.first())
        assertEquals("0123", data.pin)
        assertEquals("Synthetic secret", data.customFields.single().value)
        assertEquals(SecureCustomFieldType.HIDDEN, data.customFields.single().type)
        assertEquals("Synthetic note", item.notes)
        show { }
        show { AddEditBankCardScreen(bank, cardId = item.id, onNavigateBack = {}) }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("bank_item_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bank_item_editor").performScrollToIndex(1)
        compose.onNodeWithTag("wallet_content_notes").assertIsDisplayed()
        compose.onNodeWithTag("wallet_content_extended").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun documentMoveMenuAndRecreationKeepExistingIdentityAndSaveOrder() = runBlocking {
        val original = DocumentData(DocumentType.PASSPORT, "SYNTHETIC-001", "Synthetic Name", firstName = "Synthetic",
            address1 = "Example Street", ssn = "synthetic-sensitive")
        val id = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.DOCUMENT, title = "Synthetic Document",
            notes = "Existing note", itemData = CardWalletDataCodec.encodeDocumentData(original)))
        var saved by mutableStateOf(false)
        show { AddEditDocumentScreen(document, documentId = id, onNavigateBack = { saved = true }) }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("document_item_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("document_item_editor").performScrollToIndex(1)
        compose.onNodeWithTag("wallet_actions_identity").performClick()
        compose.onNodeWithText(context.getString(R.string.move_down)).performClick()
        compose.activityRule.scenario.recreate()
        scroll("document_item_editor", "wallet_content_address")
        assertTrue(compose.onNodeWithTag("wallet_content_address").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_identity").fetchSemanticsNode().boundsInRoot.top)
        capture("document-reordered")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = db.secureItemDao().getAllItems().first().single()
        val data = requireNotNull(document.parseDocumentData(item.itemData))
        assertEquals(original, data.copy(editorSectionOrder = emptyList()))
        assertEquals("address", data.editorSectionOrder.first())
        assertEquals("Existing note", item.notes)
    }

    @Test fun billingExtrasCollapseAndReopenWithoutMixingNameOrContactValues(): Unit = runBlocking {
        var saved by mutableStateOf(false)
        show { AddEditBillingAddressScreen(billing, onNavigateBack = { saved = true }) }
        fun input(label: Int, value: String) {
            compose.onNode(hasText(context.getString(label)) and hasSetTextAction())
                .performScrollTo().performTextReplacement(value)
        }
        input(R.string.full_name, "Original full name")
        add("billing_item_editor", "name_parts")
        input(R.string.document_first_name_label, "Independent first")
        input(R.string.document_middle_name_label, "Independent middle")
        input(R.string.document_last_name_label, "Independent last")
        closeEditor()
        add("billing_item_editor", "address_extra")
        input(R.string.wallet_district, "Synthetic district")
        input(R.string.wallet_address_line3, "Synthetic line three")
        closeEditor()
        for ((key, email) in listOf("contact" to "default@example.invalid", "home_contact" to "home@example.invalid", "work_contact" to "work@example.invalid")) {
            add("billing_item_editor", key)
            input(R.string.email, email)
            input(R.string.phone, if (key == "home_contact") "0123456789" else "0012345678")
            closeEditor()
        }
        add("billing_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performTextInput("Private address note")
        compose.onNodeWithTag("wallet_custom_value_0").performTextInput("Synthetic value")
        closeEditor()
        add("billing_item_editor", "notes")
        input(R.string.notes, "Synthetic note")
        closeEditor()
        scroll("billing_item_editor", "wallet_content_name_parts")
        compose.onNode(hasText(context.getString(R.string.document_first_name_label)) and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithTag("wallet_actions_address_extra").performClick()
        compose.onNodeWithText(context.getString(R.string.move_up)).performClick()
        capture("billing-collapsed")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = db.secureItemDao().getAllItems().first().single()
        val data = requireNotNull(billing.parseAddressData(item.itemData))
        assertEquals("Original full name", data.fullName)
        assertEquals("Independent first", data.firstName)
        assertEquals("Independent middle", data.middleName)
        assertEquals("Independent last", data.lastName)
        assertEquals("Synthetic district", data.district)
        assertEquals("Synthetic line three", data.addressLine3)
        assertEquals("default@example.invalid", data.email)
        assertEquals("home@example.invalid", data.homeEmail)
        assertEquals("work@example.invalid", data.workEmail)
        assertEquals("0123456789", data.homePhone)
        assertEquals("Synthetic value", data.customFields.single().value)
        assertEquals("Synthetic note", item.notes)
        assertEquals("address_extra", data.editorSectionOrder.first())
        show { }
        show { AddEditBillingAddressScreen(billing, addressId = item.id, onNavigateBack = {}) }
        scroll("billing_item_editor", "wallet_content_name_parts")
        compose.onNodeWithTag("wallet_content_name_parts").performClick()
        compose.onNode(hasText("Independent first") and hasSetTextAction()).assertExists()
        closeEditor()
        compose.activityRule.scenario.recreate()
        scroll("billing_item_editor", "wallet_content_address_extra")
        compose.onNodeWithTag("wallet_content_address_extra").assertIsDisplayed()
    }

    @Test fun nameHelpAndPhotoCardsStayReadableWithLargeText() {
        show {
            val density = LocalDensity.current
            val localized = remember {
                context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
                })
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f),
                androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalConfiguration provides localized.resources.configuration,
                androidx.compose.ui.platform.LocalResources provides localized.resources) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = {}) }
                }
            }
        }
        add("bank_item_editor", "name_parts")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("wallet_name_parts_help").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.none { it.hasVisualOverflow })
        assertTrue(layouts.all { it.layoutInput.density.fontScale == 1.7f })
        capture("name-parts-dark-large")
        closeEditor()
        scroll("bank_item_editor", "wallet_content_name_parts")
        compose.onNodeWithTag("wallet_content_name_parts").performClick()
        compose.onNodeWithTag("wallet_name_parts_help").assertIsDisplayed()
        closeEditor()
        add("bank_item_editor", "photos")
        compose.onNodeWithTag("dual_photo_front").performScrollTo().assertIsDisplayed()
        capture("photos-front-dark-large")
        compose.onNodeWithTag("dual_photo_back").performScrollTo().assertIsDisplayed()
        capture("photos-back-dark-large")
        compose.onNodeWithTag("wallet_detail_done").assertIsDisplayed()
        closeEditor()
    }

    @Test fun smallDarkLargeTextEditorUsesIndependentFieldsAndKeepsEmptyAddedFields() {
        var editorDensity = 0f
        show {
            val density = LocalDensity.current
            SideEffect { editorDensity = density.density }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = {}) }
                }
            }
        }
        add("bank_item_editor", "extended")
        for (key in listOf("validFromMonth", "validFromYear", "pin", "branchCode")) {
            compose.onNodeWithTag("entry_extra_add").performScrollTo().performClick()
            compose.onNodeWithTag("entry_extra_choose_$key").performScrollTo().performClick()
        }
        closeEditor()
        scroll("bank_item_editor", "wallet_content_extended")
        compose.onNodeWithTag("wallet_content_extended").performClick()
        val month = compose.onNodeWithTag("entry_extra_validFromMonth").fetchSemanticsNode().boundsInRoot
        val year = compose.onNodeWithTag("entry_extra_validFromYear").fetchSemanticsNode().boundsInRoot
        val pin = compose.onNodeWithTag("entry_extra_pin").fetchSemanticsNode().boundsInRoot
        assertEquals(month.width, pin.width, 1f)
        assertEquals(month.height, year.height, 1f)
        assertEquals("Independent rounded fields need the designed 12dp gap", 12f * editorDensity, year.top - month.bottom, 1f)
        compose.onNodeWithTag("entry_extra_branchCode").performScrollTo().performClick().performTextInput("00123")
        waitForEditorKeyboard()
        compose.onNodeWithTag("entry_extra_branchCode").assertIsDisplayed()
        compose.onNodeWithTag("wallet_detail_done").assertIsDisplayed()
        capture("extended-dark-large-keyboard")
        closeEditor()
        add("bank_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performTextInput("Synthetic private field")
        compose.onNodeWithTag("wallet_custom_value_0").performScrollTo().performClick().performTextInput("secret")
        waitForEditorKeyboard()
        compose.onNodeWithTag("wallet_custom_value_0").assertIsDisplayed()
        compose.onNodeWithTag("wallet_detail_done").assertIsDisplayed()
        capture("custom-dark-large-keyboard")
        closeEditor()
    }
}

package takagi.ru.monica.credentialexchange

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditNoteScreen
import takagi.ru.monica.ui.components.EmbeddedWalletSavedContent
import takagi.ru.monica.attachments.EmbeddedWalletEditorResult
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.NoteEditorViewModel
import takagi.ru.monica.utils.AppLocaleStringResolver
import java.io.File

class EmbeddedWalletUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: TransferFixture
    private lateinit var bank: BankCardViewModel
    private lateinit var note: NoteViewModel
    private lateinit var editor: NoteEditorViewModel
    @Before fun setup() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            assertTrue(takagi.ru.monica.security.SecureStorageStartup.prepareForEntry(context) is
                takagi.ru.monica.security.SecureStartupResult.Ready)
        }
        fixture = TransferFixture()
        bank = BankCardViewModel(fixture.secureItems, fixture.context, fixture.db.localKeePassDatabaseDao(), fixture.security,
            strings = AppLocaleStringResolver(fixture.context))
        note = NoteViewModel(fixture.secureItems, context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            securityManager = fixture.security, strings = AppLocaleStringResolver(fixture.context))
        editor = NoteEditorViewModel(persistDrafts = false)
    }
    @After fun cleanup() { bank.viewModelScope.cancel(); note.viewModelScope.cancel(); editor.viewModelScope.cancel(); runBlocking { fixture.close() } }
    @Test fun fullCardEditorSavesDraftWithoutCreatingStandaloneCard() {
        val source = SecureItem(itemType = ItemType.BANK_CARD, title = "${fixture.prefix}-card",
            itemData = """{"cardNumber":"4242424242424242","cardholderName":"ALICE","expiryMonth":"09","expiryYear":"2030","pin":"0123","future":{"keep":true}}""")
        val snapshot = EmbeddedWalletContent.create(source)
        var result: EmbeddedWalletEditorResult? = null
        compose.setContent { MaterialTheme { AddEditBankCardScreen(bank, onNavigateBack = {}, embeddedDraft = snapshot, onEmbeddedSave = { result = it }) } }
        compose.waitUntil(20000) { compose.onAllNodesWithText("ALICE").fetchSemanticsNodes().isNotEmpty() }
        screenshot("embedded-card-editor")
        compose.onNodeWithContentDescription(fixture.context.getString(R.string.save)).performClick()
        compose.waitUntil(10000) { result != null }
        assertEquals("0123", result!!.snapshot.itemData["pin"]?.jsonPrimitive?.content)
        assertEquals(true, result!!.snapshot.itemData["future"]?.jsonObject?.get("keep")?.jsonPrimitive?.boolean)
        assertEquals(source.itemData, snapshot.itemData.toString())
    }
    @Test fun fullNoteEditorRetainsFormattingAndTagsInDraft() {
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "${fixture.prefix}-note",
            itemData = """{"content":"# Heading\n- [ ] Task","tags":["work"],"isMarkdown":true,"future":7}"""))
        var result: EmbeddedWalletEditorResult? = null
        compose.setContent { MaterialTheme { AddEditNoteScreen(-1, onNavigateBack = {}, viewModel = note, editorViewModel = editor,
            embeddedDraft = snapshot, onEmbeddedSave = { result = it }) } }
        compose.waitUntil(20000) { editor.uiState.value.contentField.text.contains("Heading") }
        screenshot("embedded-note-editor")
        compose.onNodeWithContentDescription(fixture.context.getString(R.string.save)).performClick()
        compose.waitUntil(10000) { result != null }
        assertEquals("# Heading\n- [ ] Task", result!!.snapshot.itemData["content"]?.jsonPrimitive?.content)
        assertEquals(7, result!!.snapshot.itemData["future"]?.jsonPrimitive?.int)
        assertEquals("work", result!!.snapshot.itemData["tags"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }
    @Test fun passwordEditorCopiesCardAndReopensIndependentDetails() {
        val model = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            strings = AppLocaleStringResolver(fixture.context))
        val title = "${fixture.prefix}-source-card"
        val source = SecureItem(itemType = ItemType.BANK_CARD, title = title,
            itemData = """{"cardNumber":"4242424242424242","cardholderName":"COPY ALICE","expiryMonth":"09","expiryYear":"2030","pin":"0123","future":7}""")
        val sourceId = runBlocking { fixture.db.secureItemDao().insertItem(source) }
        var saved by mutableStateOf<Long?>(null)
        var detail by mutableStateOf(false)
        try {
            compose.setContent { CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
                MaterialTheme {
                    if (detail) takagi.ru.monica.ui.screens.PasswordDetailScreen(model, passwordId = saved!!,
                        biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                    else takagi.ru.monica.ui.screens.AddEditPasswordScreen(model, passwordId = null, bankCardViewModel = bank,
                        noteViewModel = note, initialStorageExplicit = true,
                        initialDraft = takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft(
                            title = "${fixture.prefix}-password", username = "alice", password = "test-password"),
                        onSaveCompleted = { saved = it }, onNavigateBack = {})
                }
            } }
            compose.waitUntil(20000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_content_add"))
            // Scroll past the fixed save FAB: LazyColumn scroll-to only guarantees layout visibility.
            compose.onNodeWithTag("password_content_editor").performTouchInput { swipeUp() }
            compose.onNodeWithTag("password_content_add").performClick()
            try {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("password_content_choose_PAYMENT").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) { screenshot("copy-menu-PAYMENT-failure"); throw failure }
            compose.onNodeWithTag("password_content_choose_PAYMENT").performScrollTo().performClick()
            compose.onNodeWithText(fixture.context.getString(R.string.embedded_copy_card)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(20000) { saved != null }
            val field = runBlocking { model.getCustomFieldsByEntryIdSync(saved!!).single { takagi.ru.monica.data.model.EmbeddedWalletContent.isMetadata(it.title) } }
            val copy = (EmbeddedWalletContent.read(field.value) as EmbeddedWalletContent.ReadResult.Available).snapshot
            assertEquals("0123", copy.itemData["pin"]?.jsonPrimitive?.content)
            assertEquals(7, copy.itemData["future"]?.jsonPrimitive?.int)
            runBlocking { fixture.db.secureItemDao().deleteItem(source.copy(id = sourceId)) }
            compose.runOnIdle { detail = true }
            compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
            // Keep the selected row clear of the detail screen's floating actions.
            compose.onNode(hasScrollToIndexAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 300f) }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("COPY ALICE") and hasAnyAncestor(hasTestTag("embedded_wallet_detail"))).fetchSemanticsNodes().isNotEmpty() }
            screenshot("embedded-card-in-password-detail")
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun passwordEditorCopiesNoteAndReopensIndependentDetails() {
        val model = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            strings = AppLocaleStringResolver(fixture.context))
        val title = "${fixture.prefix}-source-note"
        val source = SecureItem(itemType = ItemType.NOTE, title = title,
            itemData = """{"content":"Independent note body","tags":["work"],"isMarkdown":true,"future":7}""")
        val sourceId = runBlocking { fixture.db.secureItemDao().insertItem(source) }
        var saved by mutableStateOf<Long?>(null)
        var detail by mutableStateOf(false)
        try {
            compose.setContent { CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
                MaterialTheme {
                    if (detail) takagi.ru.monica.ui.screens.PasswordDetailScreen(model, passwordId = saved!!,
                        biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                    else takagi.ru.monica.ui.screens.AddEditPasswordScreen(model, passwordId = null, bankCardViewModel = bank,
                        noteViewModel = note, initialStorageExplicit = true,
                        initialDraft = takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft(
                            title = "${fixture.prefix}-password", username = "alice", password = "test-password"),
                        onSaveCompleted = { saved = it }, onNavigateBack = {})
                }
            } }
            compose.waitUntil(20000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_content_add"))
            // Scroll past the fixed save FAB: LazyColumn scroll-to only guarantees layout visibility.
            compose.onNodeWithTag("password_content_editor").performTouchInput { swipeUp() }
            compose.onNodeWithTag("password_content_add").performClick()
            try {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("password_content_choose_NOTES").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) { screenshot("copy-menu-NOTES-failure"); throw failure }
            compose.onNodeWithTag("password_content_choose_NOTES").performScrollTo().performClick()
            compose.onNode(hasText(fixture.context.getString(R.string.embedded_copy_note)) and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(20000) { saved != null }
            val field = runBlocking { model.getCustomFieldsByEntryIdSync(saved!!).single { takagi.ru.monica.data.model.EmbeddedWalletContent.isMetadata(it.title) } }
            val copy = (EmbeddedWalletContent.read(field.value) as EmbeddedWalletContent.ReadResult.Available).snapshot
            assertEquals("Independent note body", copy.itemData["content"]?.jsonPrimitive?.content)
            assertEquals(7, copy.itemData["future"]?.jsonPrimitive?.int)
            runBlocking { fixture.db.secureItemDao().deleteItem(source.copy(id = sourceId)) }
            compose.runOnIdle { detail = true }
            compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
            // Keep the selected row clear of the detail screen's floating actions.
            compose.onNode(hasScrollToIndexAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 300f) }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("Independent note body") and hasAnyAncestor(hasTestTag("embedded_wallet_detail"))).fetchSemanticsNodes().isNotEmpty() }
            screenshot("embedded-note-in-password-detail")
        } finally { model.viewModelScope.cancel() }
    }

    private fun screenshot(name: String) {
        val file = File(fixture.context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { output -> (if (name.endsWith("in-password-detail")) compose.onNodeWithTag("embedded_wallet_detail") else compose.onAllNodes(isRoot()).onLast()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
    }
}

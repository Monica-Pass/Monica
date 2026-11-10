package takagi.ru.monica.nfc.card

import android.content.Intent
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.lansync.DeviceSyncEditGate
import takagi.ru.monica.lansync.DeviceSyncEditorLease
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel

/** Real host/editors with synthetic, already-parsed reader results; this does not exercise RF. */
@RunWith(AndroidJUnit4::class)
class NfcCardEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val security by lazy { SecurityManager(context) }
    private lateinit var controller: NfcCardEntryController
    private lateinit var db: PasswordDatabase
    private lateinit var bank: BankCardViewModel
    private lateinit var document: DocumentViewModel
    private var wasUnlocked = false
    private var hostVisible by mutableStateOf(true)
    private var route by mutableStateOf("main")
    private var fontScale by mutableStateOf(1f)
    private var dark by mutableStateOf(false)
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private var navigate: () -> Unit = {}
    private var navigationCount = 0
    private var registry: SaveableStateRegistry? = null
    private val sample get() = EmvCardReadResult.BankCard(PAN, 9, 2031, HOLDER, "Visa")

    @Before fun prepareIsolatedHost() {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("legacyVaultwardenUser") == user.toString()) {
            "Use the task-owned synthetic Android user"
        }
        runBlocking { assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready) }
        wasUnlocked = SessionManager.isUnlocked.value
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repository = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        compose.runOnIdle {
            SessionManager.markUnlocked()
            bank = BankCardViewModel(repository, strings = AppLocaleStringResolver(context))
            document = DocumentViewModel(repository, strings = AppLocaleStringResolver(context))
            controller = NfcCardEntryController(compose.activity)
        }
        compose.setContent {
            val density = LocalDensity.current
            val currentRegistry = LocalSaveableStateRegistry.current
            SideEffect { registry = currentRegistry }
            CompositionLocalProvider(LocalUiSecurityManager provides security,
                LocalNfcCardEntry provides controller,
                LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { screen() }
                    if (hostVisible) NfcCardEntryHost(true, route) { navigationCount++; navigate() }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.onResume(compose.activity); controller.focus(true) }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { hostVisible = false; screen = {} }
            compose.waitForIdle()
            compose.runOnIdle {
                controller.onDestroy(compose.activity)
                bank.viewModelScope.cancel()
                document.viewModelScope.cancel()
                if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
            }
        }
        if (::db.isInitialized) db.close()
    }

    @Test fun bankScanFillsCurrentEditorPreservesUnrelatedFieldsAndRequiresTypeBeforeSave() {
        var canSave = false
        var saved = false
        show(BANK_ROUTE) {
            AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true },
                onCanSaveChanged = { canSave = it })
        }
        input("item_editor_title", "Synthetic NFC wallet")
        input("bank_branch", "Synthetic branch")
        input("entry_payment_cvv", "123")
        inject(sample)
        assertEquals(0, navigationCount)
        assertValue("entry_payment_number", PAN)
        assertValue("entry_payment_holder", HOLDER)
        assertValue("entry_payment_expiry", "09/2031")
        assertValue("entry_payment_cvv", "123")
        assertValue("item_editor_title", "Synthetic NFC wallet")
        assertValue("bank_branch", "Synthetic branch")
        compose.runOnIdle { assertFalse(canSave) }
        closeKeyboard()
        saveButton().performClick()
        compose.runOnIdle { assertFalse(saved) }
        assertTrue(runBlocking { db.secureItemDao().getAllItems().first().isEmpty() })
        for (type in CardType.entries) {
            scroll(BANK_ROOT, "nfc_card_type_${type.name}")
            compose.onNodeWithTag("nfc_card_type_${type.name}").assertIsNotSelected()
        }
        scroll(BANK_ROOT, "nfc_card_type_CREDIT")
        compose.onNodeWithTag("nfc_card_type_CREDIT").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(canSave) }
        saveButton().performClick()
        compose.waitUntil(15000) { saved }
        val item = runBlocking { db.secureItemDao().getAllItems().first().single() }
        val data = requireNotNull(bank.parseCardData(item.itemData))
        assertEquals(CardType.CREDIT, data.cardType)
        assertEquals(PAN, data.cardNumber)
        assertEquals(HOLDER, data.cardholderName)
        assertEquals("09", data.expiryMonth)
        assertEquals("2031", data.expiryYear)
        assertEquals("123", data.cvv)
        assertEquals("Synthetic branch", data.bankBranch)
        assertEquals("Synthetic NFC wallet", item.title)
        show("main") {}
        show(BANK_ROUTE) { AddEditBankCardScreen(bank, cardId = item.id, onNavigateBack = {}) }
        assertValue("entry_payment_number", PAN)
        assertValue("entry_payment_holder", HOLDER)
        assertValue("bank_branch", "Synthetic branch")
    }

    @Test fun conflictingScanCancelAndConfirmRetainAbsentAndUnrelatedBankFields() {
        var saved = false
        show(BANK_ROUTE) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        input("item_editor_title", "Keep title")
        input("entry_payment_number", SECOND_PAN)
        input("entry_payment_holder", "Manual holder")
        input("entry_payment_expiry", "08/2032")
        input("entry_payment_cvv", "456")
        closeKeyboard()
        inject(EmvCardReadResult.BankCard(PAN))
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        capture("replace-cancel")
        compose.onNodeWithTag("nfc_card_cancel").performClick()
        assertValue("entry_payment_number", SECOND_PAN)
        compose.onNodeWithTag("nfc_card_type_hint").assertDoesNotExist()
        inject(EmvCardReadResult.BankCard(PAN))
        compose.onNodeWithTag("nfc_card_confirm").performClick()
        assertValue("entry_payment_number", PAN)
        assertValue("entry_payment_holder", "Manual holder")
        assertValue("entry_payment_expiry", "08/2032")
        assertValue("entry_payment_cvv", "456")
        assertValue("item_editor_title", "Keep title")
        assertEquals(0, navigationCount)
        scroll(BANK_ROOT, "nfc_card_type_DEBIT")
        compose.onNodeWithTag("nfc_card_type_DEBIT").performClick()
        saveButton().performClick()
        compose.waitUntil(15000) { saved }
        val item = runBlocking { db.secureItemDao().getAllItems().first().single() }
        val data = requireNotNull(bank.parseCardData(item.itemData))
        assertEquals(CardType.DEBIT, data.cardType)
        assertEquals("Manual holder", data.cardholderName)
        assertEquals("08", data.expiryMonth)
        assertEquals("2032", data.expiryYear)
        assertEquals("456", data.cvv)
        assertEquals("Keep title", item.title)
    }

    @Test fun documentMismatchCancelAndNewBankReturnPreserveOriginalUnsavedDraft() {
        var page by mutableStateOf("document")
        compose.runOnIdle { navigate = { route = BANK_ROUTE; page = "bank" } }
        show(DOCUMENT_ROUTE) {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(page) {
                if (page == "document") AddEditDocumentScreen(document, initialStorageExplicit = true, onNavigateBack = {})
                else AddEditBankCardScreen(bank, initialStorageExplicit = true,
                    onNavigateBack = { route = DOCUMENT_ROUTE; page = "document" })
            }
        }
        input("item_editor_title", "Unsaved synthetic document", DOCUMENT_ROOT)
        val documentNumber = hasText(context.getString(R.string.document_number_required)) and hasSetTextAction()
        compose.onNodeWithTag(DOCUMENT_ROOT).performScrollToNode(documentNumber)
        compose.onNode(documentNumber).performTextInput("SYNTHETIC-DOCUMENT-001")
        closeKeyboard()
        inject(sample)
        compose.onNodeWithTag("nfc_card_new").assertIsDisplayed()
        capture("document-new-bank-confirmation")
        compose.onNodeWithTag("nfc_card_cancel").performClick()
        assertEquals(0, navigationCount)
        assertValue("item_editor_title", "Unsaved synthetic document", DOCUMENT_ROOT)
        inject(sample)
        compose.onNodeWithTag("nfc_card_confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, navigationCount)
        assertValue("entry_payment_number", PAN)
        assertValue("entry_payment_holder", HOLDER)
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertValue("item_editor_title", "Unsaved synthetic document", DOCUMENT_ROOT)
        compose.onNodeWithTag(DOCUMENT_ROOT).performScrollToNode(documentNumber)
        revealField(documentNumber, R.string.show, R.string.hide)
        assertEditable(compose.onNode(documentNumber), "SYNTHETIC-DOCUMENT-001")
        assertEquals(1, navigationCount)
        assertTrue(runBlocking { db.secureItemDao().getAllItems().first().isEmpty() })
    }

    @Test fun scannedEditorStateExcludesPanHolderAndExpiryFromSavedState() {
        show(BANK_ROUTE) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = {}) }
        input("item_editor_title", "Saveable synthetic title")
        inject(sample)
        assertValue("entry_payment_number", PAN)
        assertValue("entry_payment_holder", HOLDER)
        assertValue("entry_payment_expiry", "09/2031")
        // Exercise each real child text editor's local editing state before saving the entire tree.
        // Checking only the parent's rememberNfcCardField saver would miss child TextFieldValue savers.
        input("entry_payment_number", PAN)
        input("entry_payment_holder", HOLDER)
        input("entry_payment_expiry", "09/2031")
        closeKeyboard()
        var saved: Map<String, List<Any?>> = emptyMap()
        compose.runOnIdle { saved = requireNotNull(registry).performSave() }
        val text = savedStrings(saved)
        assertTrue("Control field must really be included in the captured state", text.any { it.contains("Saveable synthetic title") })
        for (secret in listOf(PAN, HOLDER, "09/2031", "2031")) {
            assertFalse("NFC-derived field must not enter saved state", text.any { it.contains(secret) })
        }
        assertTrue(runBlocking { db.secureItemDao().getAllItems().first().isEmpty() })
    }

    @Test fun lockRevokesPromptAndRejectsResultFromPreviousGenerationAfterUnlock() {
        var fills = 0
        fakeBank(NfcCardFields(SECOND_PAN, "", "", "")) { fills++ }
        inject(sample)
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        val oldGeneration = SessionManager.lockGeneration.value
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitForIdle()
        compose.onNodeWithTag("nfc_card_replace").assertDoesNotExist()
        compose.runOnIdle { controller.accept(sample, oldGeneration); controller.confirm() }
        compose.runOnIdle { SessionManager.markUnlocked() }
        compose.waitForIdle()
        compose.runOnIdle { controller.authorize(true, route, false); controller.accept(sample, oldGeneration); controller.confirm() }
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
        compose.onNodeWithTag("nfc_card_replace").assertDoesNotExist()
    }

    @Test fun activityBackgroundDiscardsPromptAndRejectsDeliveryUntilResume() {
        var fills = 0
        fakeBank(NfcCardFields(SECOND_PAN, "", "", "")) { fills++ }
        inject(sample)
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.accept(sample); controller.confirm() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("nfc_card_replace").assertDoesNotExist()
        compose.runOnIdle { controller.confirm() }
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
    }

    @Test fun coldOrLockedTagIntentDropsSensitiveExtrasAndRequiresANewTap() {
        lateinit var cold: NfcCardEntryController
        compose.runOnIdle { cold = NfcCardEntryController(compose.activity) }
        compose.waitForIdle()
        try {
            val coldIntent = syntheticTagIntent()
            compose.runOnIdle {
                cold.receive(coldIntent)
                assertTagExtrasRemoved(coldIntent)
                assertFalse(hasPendingTag(cold))
                assertEquals(R.string.nfc_card_unlock_retap, cold.ui.value.message)
                assertFalse(cold.ui.value.reading)
                cold.authorize(true, "main", true)
                cold.focus(true)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(hasPendingTag(cold))
                assertFalse(cold.ui.value.reading)
                assertEquals(R.string.nfc_card_unlock_retap, cold.ui.value.message)
                SessionManager.markLocked()
            }
            compose.waitForIdle()
            val lockedIntent = syntheticTagIntent()
            compose.runOnIdle {
                cold.receive(lockedIntent)
                assertTagExtrasRemoved(lockedIntent)
                assertFalse(hasPendingTag(cold))
                assertEquals(R.string.nfc_card_unlock_retap, cold.ui.value.message)
                assertFalse(cold.ui.value.reading)
                assertEquals(NfcCardEntryController.Prompt.NONE, cold.ui.value.prompt)
            }
            assertEquals(0, navigationCount)
        } finally {
            compose.runOnIdle { cold.onDestroy(compose.activity) }
        }
    }

    @Test fun warmTagWaitsThroughActivityResumeUntilItsEditorLifecycleRegisters() {
        lateinit var entry: DelayedEditorLifecycle
        var fills = 0
        compose.runOnIdle { entry = DelayedEditorLifecycle().apply { registry.currentState = Lifecycle.State.STARTED } }
        show(BANK_ROUTE) {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                DeviceSyncEditorLease()
                NfcCardEditor(NfcCardEditorKind.BANK, true, false, onFill = { fills++ })
            }
        }
        compose.runOnIdle {
            assertTrue("The editor lease must be active while navigation registration is delayed", DeviceSyncEditGate.isEditing)
            assertFalse(hasPendingTag(controller))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val intent = syntheticTagIntent()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("This fixture exercises an already unlocked warm launch", SessionManager.isUnlocked.value)
            controller.receive(intent)
            assertTagExtrasRemoved(intent)
            assertTrue("Background delivery should wait for the resumed host", hasPendingTag(controller))
            assertFalse(controller.ui.value.reading)
            assertNotEquals(R.string.nfc_card_busy, controller.ui.value.message)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            controller.focus(true)
            controller.authorize(true, BANK_ROUTE, false)
        }
        // Let every posted resume/focus/authorize callback run with the child still STARTED.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.runOnIdle {
            assertTrue("A waiting bank editor must not be treated as an unrelated busy editor", hasPendingTag(controller))
            assertFalse(controller.ui.value.reading)
            assertNotEquals(R.string.nfc_card_busy, controller.ui.value.message)
            entry.registry.currentState = Lifecycle.State.RESUMED
            assertTrue("Registration schedules consumption after the current lifecycle callback", hasPendingTag(controller))
        }
        // Mock Tag has an IsoDep technology but no service. This proves admission, not an RF read.
        compose.waitUntil(2500) { controller.ui.value.message == R.string.nfc_card_read_failed }
        compose.runOnIdle {
            assertFalse(hasPendingTag(controller))
            assertFalse(controller.ui.value.reading)
            assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
            entry.registry.currentState = Lifecycle.State.DESTROYED
        }
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
    }

    @Test fun hostDisposalRevokesPendingAndFutureResultsWhileEditorStillExists() {
        var fills = 0
        fakeBank(NfcCardFields(SECOND_PAN, "", "", "")) { fills++ }
        inject(sample)
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        compose.runOnIdle { hostVisible = false }
        compose.waitForIdle()
        compose.runOnIdle { controller.accept(sample); controller.confirm() }
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
        assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
    }

    @Test fun replacementOwnerOnSameRouteCannotReceivePreviousOwnersConfirmation() {
        var firstFills = 0
        var secondFills = 0
        val first = editor(Any(), NfcCardFields(SECOND_PAN, "", "", "")) { firstFills++ }
        val second = editor(Any(), NfcCardFields("", "", "", "")) { secondFills++ }
        compose.runOnIdle { controller.register(first) }
        inject(sample)
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        compose.runOnIdle { controller.register(second); controller.confirm() }
        compose.waitForIdle()
        assertEquals(0, firstFills)
        assertEquals(0, secondFills)
        assertEquals(0, navigationCount)
        compose.onNodeWithTag("nfc_card_replace").assertDoesNotExist()
    }

    @Test fun unregisteringEditorCancelsPendingBeforeNewOwnerRegisters() {
        var fills = 0
        val owner = Any()
        compose.runOnIdle { controller.register(editor(owner, NfcCardFields(SECOND_PAN, "", "", "")) { fills++ }) }
        inject(sample)
        compose.runOnIdle {
            controller.unregister(owner)
            controller.register(editor(Any(), NfcCardFields("", "", "", "")) { fills++ })
            controller.confirm()
        }
        compose.waitForIdle()
        assertEquals(0, fills)
        assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
    }

    @Test fun busyBankEditorRejectsResultWithoutChangingFieldsOrOpeningAnotherEditor() {
        var fills = 0
        show(BANK_ROUTE) { NfcCardEditor(NfcCardEditorKind.BANK, true, true, onFill = { fills++ }) }
        inject(sample)
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
        assertEquals(R.string.nfc_card_busy, controller.ui.value.message)
        assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
    }

    @Test fun unrelatedBlockingEditorRejectsNavigationEvenOnMainRoute() {
        show("main") { NfcCardBlockingEditor() }
        inject(sample)
        assertEquals(0, navigationCount)
        assertEquals(R.string.nfc_card_busy, controller.ui.value.message)
        assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
    }

    @Test fun routeChangeClearsConfirmationBeforeAResultCanReachAnotherPage() {
        var fills = 0
        fakeBank(NfcCardFields(SECOND_PAN, "", "", "")) { fills++ }
        inject(sample)
        compose.onNodeWithTag("nfc_card_replace").assertIsDisplayed()
        compose.runOnIdle { route = "settings" }
        compose.waitForIdle()
        compose.runOnIdle { controller.confirm() }
        assertEquals(0, fills)
        assertEquals(0, navigationCount)
        compose.onNodeWithTag("nfc_card_replace").assertDoesNotExist()
    }

    @Test fun mainRouteOpensOneNewBankAndDeliversThePendingResultOnlyOnce() {
        var page by mutableStateOf(false)
        var fills = 0
        var received: EmvCardReadResult.BankCard? = null
        compose.runOnIdle { navigate = { route = BANK_ROUTE; page = true } }
        show("main") {
            if (page) NfcCardEditor(NfcCardEditorKind.BANK, true, false, onFill = { fills++; received = it })
        }
        inject(sample)
        compose.waitForIdle()
        assertEquals(1, navigationCount)
        assertEquals(1, fills)
        assertEquals(PAN, received?.number)
        compose.runOnIdle { controller.authorize(true, BANK_ROUTE, false); controller.confirm() }
        assertEquals(1, navigationCount)
        assertEquals(1, fills)
    }

    @Test fun scanButtonReportsActualHardwareAvailabilityWithoutNavigationOrDraft() {
        show("main") { NfcCardScanButton() }
        compose.onNodeWithTag("nfc_card_scan").performClick()
        val adapter = NfcAdapter.getDefaultAdapter(context)
        val expected = when {
            adapter == null -> R.string.nfc_card_unavailable
            !adapter.isEnabled -> R.string.nfc_card_disabled
            else -> R.string.nfc_card_scan_hint
        }
        assertEquals(expected, controller.ui.value.message)
        assertEquals(0, navigationCount)
        assertEquals(NfcCardEntryController.Prompt.NONE, controller.ui.value.prompt)
        assertTrue(runBlocking { db.secureItemDao().getAllItems().first().isEmpty() })
    }

    @Test fun darkLargeTextKeepsTypeSelectionReadableAndCanSavePrepaidCard() {
        var saved = false
        compose.runOnIdle { fontScale = 1.5f; dark = true }
        show(BANK_ROUTE) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        inject(sample)
        scroll(BANK_ROOT, "nfc_card_type_hint")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("nfc_card_type_hint").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.layoutInput.density.fontScale == 1.5f && !it.hasVisualOverflow })
        capture("bank-dark-large-type-hint")
        for (type in CardType.entries) {
            scroll(BANK_ROOT, "nfc_card_type_${type.name}")
            compose.onNodeWithTag("nfc_card_type_${type.name}").assertIsDisplayed().performClick().assertIsSelected()
        }
        scroll(BANK_ROOT, "nfc_card_type_PREPAID")
        compose.onNodeWithTag("nfc_card_type_PREPAID").performClick().assertIsSelected()
        capture("bank-dark-large-prepaid")
        saveButton().performClick()
        compose.waitUntil(15000) { saved }
        val item = runBlocking { db.secureItemDao().getAllItems().first().single() }
        assertEquals(CardType.PREPAID, requireNotNull(bank.parseCardData(item.itemData)).cardType)
    }

    private fun show(currentRoute: String, content: @Composable () -> Unit) {
        compose.runOnIdle { route = currentRoute; screen = content }
        compose.waitForIdle()
    }

    private fun inject(card: EmvCardReadResult.BankCard) {
        compose.runOnIdle { controller.accept(card) }
        compose.waitForIdle()
    }

    private fun fakeBank(fields: NfcCardFields, fill: (EmvCardReadResult.BankCard) -> Unit) {
        show(BANK_ROUTE) { NfcCardEditor(NfcCardEditorKind.BANK, true, false, fields, fill) }
    }

    private fun editor(owner: Any, fields: NfcCardFields, fill: (EmvCardReadResult.BankCard) -> Unit) =
        NfcCardEntryController.Editor(owner, NfcCardEditorKind.BANK, { false }, { fields }, fill)

    private fun scroll(root: String, tag: String) {
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag(tag))
    }

    private fun input(tag: String, value: String, root: String = BANK_ROOT) {
        scroll(root, tag)
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun assertValue(tag: String, value: String, root: String = BANK_ROOT) {
        scroll(root, tag)
        if (tag == "entry_payment_number" || tag == "entry_payment_cvv") {
            revealField(hasTestTag(tag), R.string.show_password, R.string.hide_password)
        }
        assertEditable(compose.onNodeWithTag(tag), value)
    }

    private fun revealField(field: SemanticsMatcher, showLabel: Int, hideLabel: Int) {
        val show = hasContentDescription(context.getString(showLabel)) and hasAnyAncestor(field)
        if (compose.onAllNodes(show).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(show).performClick()
        }
        // Verify the real visibility action changed state before comparing the complete value.
        compose.onNode(hasContentDescription(context.getString(hideLabel)) and hasAnyAncestor(field)).assertExists()
    }

    private fun assertEditable(node: SemanticsNodeInteraction, value: String) {
        assertEquals(value, node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun saveButton() = compose.onNodeWithContentDescription(context.getString(R.string.save))

    private class DelayedEditorLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun syntheticTagIntent(): Intent {
        // Match AOSP Tag.writeToParcel/CREATOR, using only public Parcelable APIs.
        // API 34+ includes the cookie; the API 32/33 layouts omit it. No NFC service or Binder.
        val id = byteArrayOf(1, 2, 3, 4)
        val parcel = Parcel.obtain()
        val tag = try {
            parcel.writeInt(id.size)
            parcel.writeByteArray(id)
            parcel.writeInt(1)
            parcel.writeIntArray(intArrayOf(3)) // TagTechnology.ISO_DEP
            parcel.writeTypedArray(arrayOf(Bundle()), 0)
            parcel.writeInt(0) // mock service handle
            if (Build.VERSION.SDK_INT >= 34) parcel.writeLong(0L)
            parcel.writeInt(1) // isMock: CREATOR must not read an NFC service Binder
            parcel.setDataPosition(0)
            Tag.CREATOR.createFromParcel(parcel).also { created ->
                assertEquals("Synthetic Tag parcel must be fully consumed", 0, parcel.dataAvail())
                assertTrue("Synthetic identifier must round-trip", created.id.contentEquals(id))
                assertArrayEquals(arrayOf(android.nfc.tech.IsoDep::class.java.name), created.techList)
            }
        } finally { parcel.recycle() }
        return Intent(NfcAdapter.ACTION_TECH_DISCOVERED)
            .putExtra(NfcAdapter.EXTRA_TAG, tag)
            .putExtra(NfcAdapter.EXTRA_ID, id)
            .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, emptyArray<android.os.Parcelable>())
    }

    private fun assertTagExtrasRemoved(intent: Intent) {
        assertFalse(intent.hasExtra(NfcAdapter.EXTRA_TAG))
        assertFalse(intent.hasExtra(NfcAdapter.EXTRA_ID))
        assertFalse(intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES))
    }

    private fun hasPendingTag(value: NfcCardEntryController): Boolean {
        val field = NfcCardEntryController::class.java.getDeclaredField("pendingTag")
        field.isAccessible = true
        return field.get(value) != null
    }

    private fun closeKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun savedStrings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is CharSequence -> listOf(value.toString())
        is Bundle -> value.keySet().flatMap { savedStrings(value.get(it)) }
        is Map<*, *> -> value.entries.flatMap { savedStrings(it.value) }
        is Iterable<*> -> value.flatMap(::savedStrings)
        is Array<*> -> value.flatMap(::savedStrings)
        else -> listOf(value.toString())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val file = File(context.filesDir, "nfc-card-entry-327/$name.png").apply { parentFile?.mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val PAN = "4111111111111111"
        const val SECOND_PAN = "5555555555554444"
        const val HOLDER = "SYNTHETIC NFC HOLDER"
        const val BANK_ROUTE = "add_edit_bank_card/{cardId}"
        const val DOCUMENT_ROUTE = "add_edit_document/{documentId}"
        const val BANK_ROOT = "bank_item_editor"
        const val DOCUMENT_ROOT = "document_item_editor"
    }
}

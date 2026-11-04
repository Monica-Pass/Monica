package takagi.ru.monica.credentialexchange

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.bringAboveFloatingActions
import takagi.ru.monica.ui.closeFocusedKeyboard
import takagi.ru.monica.ui.components.PasswordQrContentEditor
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File

/** Actual editor routes with synthetic data. Captures need separate visual inspection. */
@RunWith(AndroidJUnit4::class)
class QrEditorShapeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: TransferFixture
    private lateinit var model: PasswordViewModel
    private lateinit var notesModel: NoteViewModel
    private var visible by mutableStateOf(true)

    @Before fun setup() {
        fixture = TransferFixture()
        model = newModel()
        notesModel = NoteViewModel(fixture.secureItems, strings = AppLocaleStringResolver(fixture.context))
    }

    private fun newModel() = PasswordViewModel(fixture.passwords, fixture.security,
        customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
        localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))

    @After fun cleanup() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        notesModel.viewModelScope.cancel()
        model.viewModelScope.cancel()
        runBlocking { fixture.close() }
    }

    @Test fun scanCallbackPreservesEditorInputAndExistingTrimmedSaveContract() {
        // Drives the production scanner callback/result contract; no camera claim.
        val scanned = "  https://example.invalid/qr?a=1&b=%20\r\n第二行;QR 🔑  "
        val edited = scanned + "\nmanual suffix  "
        // Existing grouped local saves trim only the outer whitespace, including BARCODE.
        // Keep exact scan/editor assertions separate from the established persisted contract.
        val persisted = edited.trim()
        var pending by mutableStateOf<String?>(null)
        var editingId by mutableStateOf<Long?>(null)
        var generation by mutableIntStateOf(0)
        var callbacks = 0
        var consumed = 0
        var saved: Long? = null
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalUiSecurityManager provides fixture.security) {
                MaterialTheme {
                    key(generation) {
                        AddEditPasswordScreen(model, noteViewModel = notesModel, passwordId = editingId,
                            initialStorageExplicit = true, initialLoginType = "barcode",
                            initialDraft = AddEditPasswordInitialDraft(title = fixture.prefix),
                            pendingQrResult = pending,
                            onScanAuthenticatorQrCode = { callbacks++; pending = scanned },
                            onConsumePendingQrResult = { consumed++; pending = null },
                            onSaveCompleted = { saved = it }, onNavigateBack = {})
                    }
                }
            }
        }
        awaitTag("barcode_editor_scan")
        showEditorNode("barcode_editor_scan").performClick()
        compose.waitUntil(10_000) { consumed == 1 }
        compose.runOnIdle { assertEquals(1, callbacks); assertNull(pending) }
        showEditorNode("barcode_editor_payload").assertTextContains(scanned).performTextReplacement(edited)
        compose.onNodeWithTag("barcode_editor_payload").assertTextContains(edited)
        closeFocusedKeyboard()
        openNotes()
        compose.onNodeWithTag("password_content_notes").performTextReplacement("Notes remain separate\nwith another line")
        closeFocusedKeyboard()
        compose.onNodeWithTag("content_detail_back").performClick()
        openNotes()
        compose.onNodeWithTag("password_content_notes").assertTextContains("Notes remain separate\nwith another line")
        compose.onNodeWithTag("content_detail_back").performClick()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { saved != null }
        val id = requireNotNull(saved)
        val stored = runBlocking { requireNotNull(fixture.db.passwordEntryDao().getPasswordEntryById(id)) }
        assertEquals(persisted, fixture.security.decryptDataIfMonicaCiphertext(stored.password))
        assertEquals("Notes remain separate\nwith another line", fixture.security.decryptDataIfMonicaCiphertext(stored.notes))
        assertTrue(stored.loginType.equals("barcode", ignoreCase = true))
        assertEquals("", stored.website)
        assertEquals("", stored.username)
        val reopenedModel = newModel()
        try {
            val reread = runBlocking { requireNotNull(reopenedModel.getPasswordEntryById(id)) }
            assertEquals(persisted, reread.password)
            compose.runOnIdle { editingId = id; generation++ }
            awaitTag("barcode_editor_payload")
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("barcode_editor_payload") and hasText(persisted, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            showEditorNode("barcode_editor_payload").assertTextContains(persisted)
            openNotes()
            compose.onNodeWithTag("password_content_notes").assertTextContains("Notes remain separate\nwith another line")
            compose.runOnIdle { assertEquals("Reopening must not consume the scan twice", 1, consumed) }
        } finally { reopenedModel.viewModelScope.cancel() }
    }

    @Test fun barcodeLightNormalShowsIndependentPayloadScanAndNotes() = captureBarcode(false, 1f)
    @Test fun barcodeDarkNormalShowsIndependentPayloadScanAndNotes() = captureBarcode(true, 1f)
    @Test fun barcodeLightLargeShowsIndependentPayloadScanAndNotes() = captureBarcode(false, 1.5f)
    @Test fun barcodeDarkLargeShowsIndependentPayloadScanAndNotes() = captureBarcode(true, 1.5f)

    @Test fun passwordNotesLightNormalKeepsDraftAfterReturning() = capturePasswordNotes(false, 1f)
    @Test fun passwordNotesDarkLargeKeepsDraftAfterReturning() = capturePasswordNotes(true, 1.5f)

    private fun capturePasswordNotes(dark: Boolean, scale: Float) {
        val label = "password-notes-${if (dark) "dark" else "light"}-${if (scale > 1) "large" else "normal"}"
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalUiSecurityManager provides fixture.security,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    AddEditPasswordScreen(model, noteViewModel = notesModel, passwordId = null,
                        initialStorageExplicit = true,
                        initialDraft = AddEditPasswordInitialDraft(title = "Synthetic password", username = "example-user",
                            password = "synthetic-password"), onNavigateBack = {})
                }
            }
        }
        awaitTag("password_content_editor")
        val editor = compose.onNodeWithTag("password_content_editor")
        editor.performScrollToNode(hasTestTag("password_content_add"))
        compose.bringAboveFloatingActions(compose.onNodeWithTag("password_content_add"), editor)
        compose.onNodeWithTag("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_NOTES").performScrollTo().performClick()
        awaitTag("password_content_notes")
        compose.onNodeWithTag("password_content_notes").performTextReplacement("Password notes stay separate\n备注草稿保留")
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_content_notes").performScrollTo().assertIsDisplayed()
        assertFieldLayout("password_content_notes", scale, "$label-field")
        capture("$label-field")
        compose.onNodeWithTag("password_note_copy").performScrollTo().assertIsDisplayed()
        capture("$label-actions")
        compose.onNodeWithTag("content_detail_back").performClick()
        editor.performScrollToNode(hasTestTag("content_panel_NOTES"))
        compose.bringAboveFloatingActions(compose.onNodeWithTag("content_panel_NOTES"), editor)
        compose.onNodeWithTag("content_panel_NOTES").performClick()
        compose.onNodeWithTag("password_content_notes").assertTextContains("Password notes stay separate\n备注草稿保留")
    }

    private fun captureBarcode(dark: Boolean, scale: Float) {
        val label = "barcode-${if (dark) "dark" else "light"}-${if (scale > 1) "large" else "normal"}"
        var pending by mutableStateOf<String?>(null)
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalUiSecurityManager provides fixture.security,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    AddEditPasswordScreen(model, noteViewModel = notesModel, passwordId = null,
                        initialStorageExplicit = true, initialLoginType = "barcode",
                        initialDraft = AddEditPasswordInitialDraft(title = "Synthetic QR / 二维码"),
                        pendingQrResult = pending,
                        onScanAuthenticatorQrCode = { pending = "https://example.invalid/ticket\nQR text / 二维码内容" },
                        onConsumePendingQrResult = { pending = null }, onNavigateBack = {})
                }
            }
        }
        awaitTag("barcode_editor_scan")
        showEditorNode("barcode_editor_scan").performClick()
        showEditorNode("barcode_editor_payload").assertTextContains("https://example.invalid/ticket", substring = true)
        assertFieldLayout("barcode_editor_payload", scale, "$label-payload")
        capture("$label-payload")
        showEditorNode("barcode_editor_scan").assertIsDisplayed()
        capture("$label-scan")
        openNotes()
        compose.onNodeWithTag("password_content_notes").performTextReplacement("Synthetic notes\n备注与笔记操作分开\nA third line")
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_content_notes").performScrollTo().assertIsDisplayed()
        assertFieldLayout("password_content_notes", scale, "$label-notes")
        capture("$label-notes")
        compose.onNodeWithTag("password_note_copy").performScrollTo().assertIsDisplayed()
        capture("$label-note-actions")
        compose.onNodeWithTag("content_detail_back").performClick()
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
    }

    @Test fun literalQrSheetKeepsValuesAndDefersSecretReadAtLargeFont() {
        val literal = "  QR literal\nline two  "
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE)
            .edited("Synthetic QR", mapOf("mode" to "literal", "content" to literal, "notes" to "Synthetic notes"))
        var reads = 0
        var saved: PasswordContentBlocks.Block? = null
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PasswordQrContentEditor(block, readValues = { reads++; error("Literal mode must not read credentials") },
                        onSave = { saved = it }, onDismiss = {})
                }
            }
        }
        awaitTag("block_field_content")
        compose.onNodeWithTag("block_field_content").performScrollTo()
        assertFieldLayout("block_field_content", 1.5f, "qr-template-literal-dark-large-content")
        capture("qr-template-literal-dark-large-content")
        compose.onNodeWithTag("block_field_notes").performScrollTo()
        assertFieldLayout("block_field_notes", 1.5f, "qr-template-literal-dark-large-notes")
        capture("qr-template-literal-dark-large-notes")
        compose.onNodeWithTag("block_save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(literal, saved!!.value("content"))
        assertEquals("literal", saved!!.value("mode"))
        assertEquals(0, reads)
    }

    private fun awaitTag(tag: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun showEditorNode(tag: String): SemanticsNodeInteraction {
        val editor = compose.onNodeWithTag("password_classic_editor")
        editor.performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).also { compose.bringAboveFloatingActions(it, editor) }
    }

    private fun openNotes() {
        showEditorNode("content_panel_NOTES").performClick()
        awaitTag("password_content_notes")
    }

    /** Reads the actual editable text layout in its own window, not the host's LocalDensity.
     * Rectangle/line bounds catch clipping of visible text. Rounded corner pixels are inspected separately.
     */
    private fun assertFieldLayout(tag: String, expectedScale: Float, evidenceName: String) {
        compose.waitForIdle()
        val target = compose.onNodeWithTag(tag)
        target.assertIsDisplayed()
        val node = target.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        var accepted = false
        val failure = runCatching {
            target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                accepted = get(layouts)
            }
        }.exceptionOrNull()
        val directory = File(fixture.context.filesDir, "editor-shapes-325").apply { mkdirs() }
        // Write the actual measurements before asserting, including on a failed scale check.
        // Record lengths/geometry only, never field values.
        File(directory, "$evidenceName-layout.txt").writeText(buildString {
            appendLine("tag=$tag")
            appendLine("expectedFontScale=$expectedScale")
            appendLine("layoutActionAccepted=$accepted")
            appendLine("layoutCount=${layouts.size}")
            appendLine("unclippedSizePx=${node.size}")
            appendLine("clippedBoundsInRootPx=${node.boundsInRoot}")
            failure?.let { appendLine("layoutActionFailure=${it.javaClass.name}") }
            layouts.forEachIndexed { index, layout ->
                appendLine("layout[$index]: density=${layout.layoutInput.density.density} fontScale=${layout.layoutInput.density.fontScale} " +
                    "sizePx=${layout.size} lines=${layout.lineCount} textLength=${layout.layoutInput.text.length} visualOverflow=${layout.hasVisualOverflow}")
                for (line in 0 until layout.lineCount) {
                    appendLine("line[$index,$line]: left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                        "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} ellipsized=${layout.isLineEllipsized(line)}")
                }
            }
        })
        failure?.let { throw it }
        assertTrue("$tag must expose its actual editable text layout", accepted && layouts.isNotEmpty())
        layouts.forEachIndexed { index, layout ->
            assertEquals("$tag layout $index must retain the requested font scale in its actual window",
                expectedScale, layout.layoutInput.density.fontScale, 0.001f)
            assertTrue("$tag fixture text must have been laid out", layout.layoutInput.text.isNotEmpty())
            assertFalse("$tag layout $index must not truncate/overflow the visible fixture text", layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) {
                assertFalse("$tag line $line must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("$tag line $line left edge must fit", layout.getLineLeft(line) >= -1f)
                assertTrue("$tag line $line right edge must fit", layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("$tag line $line top edge must fit", layout.getLineTop(line) >= -1f)
                assertTrue("$tag line $line bottom edge must fit", layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        assertEquals("$tag must not be horizontally clipped by a parent or viewport",
            node.size.width.toFloat(), node.boundsInRoot.width, 1f)
        assertEquals("$tag must not be vertically clipped by a parent or viewport",
            node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(400)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = File(fixture.context.filesDir, "editor-shapes-325").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

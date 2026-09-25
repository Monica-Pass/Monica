package takagi.ru.monica.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode

class MonicaKeyboardLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(MonicaImeUiState())
    private lateinit var editor: EditText
    private var density = 1f
    private var enters = 0
    private var switched = 0

    private fun show(width: Int = 360, dark: Boolean = false, fontScale: Float = 1f, landscape: Boolean = false) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.US)
                if (landscape) orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            val context = LocalContext.current.createConfigurationContext(config)
            density = if (landscape) 1f else LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(density, fontScale)) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    AndroidView(factory = { EditText(it).apply {
                        editor = this; inputType = InputType.TYPE_CLASS_TEXT
                        showSoftInputOnFocus = false; requestFocus()
                    } }, modifier = Modifier.height(48.dp).fillMaxWidth())
                    Box(Modifier.width(width.dp).testTag("keyboard_capture")) {
                        MonicaImeContent(settings = AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                            uiState = state, onDatabaseScopeSelected = {}, onInsertPassword = {}, onInsertUsername = {},
                            onInsertWebsite = {}, onSmartFillPassword = {}, onInsertAuthenticatorCode = {},
                            onInsertCardWalletValue = {}, onSmartFillCardWallet = {},
                            onKeyPressed = ::type, onBackspace = { connection().deleteSurroundingText(1, 0) },
                            onDeleteAll = { editor.text.clear() }, onUndoDeleteAll = {}, onEnter = { enters++ },
                            onSpace = { type(" ") }, onShiftToggle = { state = state.copy(isUppercase = !state.isUppercase) },
                            onKeyboardModeChange = { mode -> state = state.copy(keyboardMode = mode,
                                pinDigits = if (mode == MonicaKeyboardMode.NUMBERS)
                                    createImePinDigits(state.keyboardOptions.scramblePin, Random(12)) else state.pinDigits) },
                            onOpenUnlockApp = {}, onOpenAutofillSettings = {}, onSearchEditRequested = {},
                            onSearchEditFinished = {}, onSearchCleared = {},
                            onPanelSelected = { state = state.copy(activePanel = it) },
                            onSwitchInputMethod = { switched++ }, onDismiss = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("ime_key_grid").assertIsDisplayed()
    }

    private fun connection() = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
    private fun type(value: String) { connection().commitText(value, 1) }
    private fun bounds(id: String) = compose.onNodeWithTag(id).fetchSemanticsNode().boundsInRoot
    private fun tap(id: String) = compose.onNodeWithTag("ime_key_$id").performClick()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("ime-improvements"), "$name.png")
        file.outputStream().use { compose.onNodeWithTag("keyboard_capture").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lettersUseOneGridAndRealInputKeepsShiftDeleteAndEnterWorking() {
        show()
        val first = bounds("ime_key_letter_q")
        "qwertyuiopasdfghjklzxcvbnm".forEach {
            val key = bounds("ime_key_letter_$it")
            assertEquals(first.width, key.width, 1f)
            assertEquals(first.height, key.height, 1f)
        }
        assertTrue(bounds("ime_key_letter_a").left > first.left)
        assertEquals(bounds("ime_key_shift").width, bounds("ime_key_delete").width, 1f)
        assertEquals(first.height, bounds("ime_key_enter").height, 1f)
        tap("letter_q"); tap("shift"); tap("letter_q"); tap("space"); tap("period"); tap("delete"); tap("enter")
        compose.runOnIdle { assertEquals("qQ ", editor.text.toString()); assertEquals(1, enters) }
        tap("switch")
        compose.runOnIdle { assertEquals(1, switched) }
        capture("letters-light")
    }

    @Test fun shuffledNumbersHaveEqualHeightsAndNeverMoveDuringTyping() {
        state = state.copy(keyboardMode = MonicaKeyboardMode.NUMBERS,
            keyboardOptions = ImeKeyboardOptions(scramblePin = true, hidePinPreview = true),
            pinDigits = createImePinDigits(true, Random(24)))
        show(width = 320, dark = true, fontScale = 1.5f)
        val before = (0..9).associateWith { bounds("ime_key_digit_$it") }
        val height = before.getValue(0).height
        before.values.forEach { assertEquals(height, it.height, 1f) }
        assertEquals(height, bounds("ime_key_enter").height, 1f)
        assertEquals(height, bounds("ime_key_delete").height, 1f)
        listOf(0, 9, 2, 6).forEach { tap("digit_$it") }
        compose.runOnIdle { assertEquals("0926", editor.text.toString()) }
        before.forEach { (digit, rect) -> assertEquals(rect, bounds("ime_key_digit_$digit")) }
        compose.onNodeWithTag("ime_key_digit_5").performTouchInput { down(center) }
        compose.onNodeWithTag("ime_key_preview", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("ime_key_digit_5").performTouchInput { up() }
        capture("numbers-shuffled-dark-large")
    }

    @Test fun numberPreviewCanBeDisabledAndPasswordFieldsSuppressIt() {
        state = state.copy(keyboardMode = MonicaKeyboardMode.NUMBERS)
        show()
        val key = compose.onNodeWithTag("ime_key_digit_1")
        key.performTouchInput { down(center) }
        capture("numbers-preview")
        compose.onNodeWithTag("ime_key_preview", useUnmergedTree = true).assertIsDisplayed()
        key.performTouchInput { up() }
        compose.runOnIdle { state = state.copy(keyboardOptions = ImeKeyboardOptions(hidePinPreview = true)) }
        key.performTouchInput { down(center) }
        compose.onNodeWithTag("ime_key_preview", useUnmergedTree = true).assertDoesNotExist()
        key.performTouchInput { up() }
        compose.runOnIdle { state = state.copy(keyboardOptions = ImeKeyboardOptions(), isSensitiveInput = true) }
        key.performTouchInput { down(center) }
        compose.onNodeWithTag("ime_key_preview", useUnmergedTree = true).assertDoesNotExist()
        key.performTouchInput { up() }
        compose.runOnIdle { assertEquals("111", editor.text.toString()) }
    }

    @Test fun modeSwitchingAndNarrowToolbarStayReachable() {
        show(width = 320, dark = true, fontScale = 1.5f)
        val root = bounds("keyboard_capture")
        listOf("keyboard", "passwords", "authenticators", "documents", "generator", "more", "hide").forEach {
            val button = bounds("ime_toolbar_$it")
            assertTrue(button.left >= root.left && button.right <= root.right)
            assertEquals(48f * density, button.height, 1f)
        }
        tap("mode"); tap("digit_4"); tap("mode"); tap("symbol_64"); tap("mode"); tap("letter_a")
        compose.runOnIdle { assertEquals("4@a", editor.text.toString()) }
        capture("letters-dark-narrow")
    }

    @Test fun landscapeCapsTheWidthAndKeepsFourRowsWithinTheKeyboard() {
        show(width = 760, landscape = true)
        val grid = bounds("ime_key_grid")
        assertEquals(584f, grid.width, 1f)
        assertEquals(168f, grid.height, 1f)
        assertTrue(bounds("ime_key_enter").bottom <= grid.bottom + 1f)
        assertEquals(36f, bounds("ime_key_enter").height, 1f)
        capture("letters-landscape")
    }

    @Test fun deleteRepeatsOnHoldAndSwipeUpStillClearsTheField() {
        show()
        compose.runOnIdle { editor.setText("1234567890"); editor.setSelection(editor.length()) }
        val delete = compose.onNodeWithTag("ime_key_delete")
        delete.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        delete.performTouchInput { up() }
        compose.runOnIdle { assertTrue("Held delete should remove several characters: ${editor.length()} remain", editor.length() in 1..8) }
        delete.performTouchInput {
            down(center)
            moveTo(androidx.compose.ui.geometry.Offset(center.x, -40f * density))
            up()
        }
        compose.runOnIdle { assertEquals("", editor.text.toString()) }
    }
}

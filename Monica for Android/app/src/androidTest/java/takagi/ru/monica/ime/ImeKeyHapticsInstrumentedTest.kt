package takagi.ru.monica.ime

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.theme.MonicaTheme

/** Exercise the real key handlers and their host-window haptic route, without claiming motor output. */
class ImeKeyHapticsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var enabled by mutableStateOf(false)
    private var mode by mutableStateOf(MonicaKeyboardMode.LETTERS)
    private val requests = mutableListOf<Int>()
    private val typed = mutableListOf<String>()

    private fun show() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = object : View(context) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                requests += feedbackConstant
                return true
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                MonicaTheme {
                    Box(Modifier.size(360.dp, 248.dp)) {
                        MonicaKeyboard(mode = mode, isUppercase = false,
                            pinDigits = StandardImePinDigits, hideKeyFeedback = true,
                            keyVibrationEnabled = enabled, onKeyPressed = { typed += it },
                            onBackspace = {}, onDeleteAll = {}, onEnter = {}, onSpace = {},
                            onShiftToggle = {}, onKeyboardModeChange = { mode = it },
                            onSwitchInputMethod = {})
                    }
                }
            }
        }
    }

    @Test fun liveToggleControlsTouchAndAccessibilityKeyboardFeedback() {
        show()
        val key = compose.onNodeWithTag("ime_key_letter_q")
        key.performTouchInput { click() }
        compose.runOnIdle { assertEquals(emptyList<Int>(), requests); enabled = true }
        key.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), requests) }
        key.performClick()
        compose.runOnIdle {
            assertEquals(List(2) { HapticFeedbackConstants.KEYBOARD_TAP }, requests)
            enabled = false
        }
        key.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, requests.size); assertEquals(listOf("q", "q", "q", "q"), typed) }
    }

    @Test fun numberSymbolAndActionKeysUseTheKeyboardWindow() {
        enabled = true
        show()
        compose.onNodeWithTag("ime_key_mode").performClick()
        compose.onNodeWithTag("ime_key_digit_1").performTouchInput { click() }
        compose.onNodeWithTag("ime_key_delete").performTouchInput { click() }
        compose.onNodeWithTag("ime_key_mode").performClick()
        compose.onNodeWithTag("ime_key_symbol_64").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(List(5) { HapticFeedbackConstants.KEYBOARD_TAP }, requests)
            assertEquals(listOf("1", "@"), typed)
        }
    }
}

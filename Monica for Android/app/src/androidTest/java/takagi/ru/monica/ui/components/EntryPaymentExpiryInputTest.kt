package takagi.ru.monica.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.suggestions.CommonInfoTestActivity

@RunWith(AndroidJUnit4::class)
class EntryPaymentExpiryInputTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val field get() = compose.onNodeWithTag("entry_payment_expiry")

    @After fun clearHostContent() {
        compose.runOnUiThread { CommonInfoTestActivity.content = {} }
    }

    private fun setExpiryContent(content: @Composable () -> Unit) {
        // The host reinstalls this composition if launch-time configuration changes recreate it.
        compose.runOnUiThread { CommonInfoTestActivity.content = content }
        awaitExpiryField()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitExpiryField() {
        compose.waitUntilExactlyOneExists(hasTestTag("entry_payment_expiry"))
    }

    private fun showWallet(filled: Boolean) {
        var month by mutableStateOf("")
        var year by mutableStateOf("")
        setExpiryContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFilledEntryForm provides filled) {
                    EntryPaymentFields("", "", EntryPaymentFormat.joinExpiry(month, year), "",
                        {}, {}, { value ->
                            val parts = EntryPaymentFormat.splitExpiry(value)
                            month = parts.first
                            year = parts.second
                        }, {})
                }
            }
        }
    }

    private fun assertExpiry(text: String, cursor: Int = text.length) {
        val semantics = field.fetchSemanticsNode().config
        assertEquals(text, semantics[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(cursor), semantics[SemanticsProperties.TextSelectionRange])
    }

    @Test fun filledWalletKeepsCursorAfterFirstYearDigit() {
        showWallet(filled = true)
        field.performTextInput("0")
        assertExpiry("0")
        field.performTextInput("7")
        assertExpiry("07")
        field.performTextInput("3")
        assertExpiry("07/3")
        field.performTextInput("1")
        assertExpiry("07/31")
    }

    @Test fun outlinedWalletKeepsCursorAfterFirstYearDigit() {
        showWallet(filled = false)
        field.performTextInput("0")
        field.performTextInput("7")
        field.performTextInput("3")
        assertExpiry("07/3")
        field.performTextInput("1")
        assertExpiry("07/31")
    }

    @Test fun walletAcceptsFourDigitYearTypedOneDigitAtATime() {
        showWallet(filled = true)
        "092030".forEach { field.performTextInput(it.toString()) }
        assertExpiry("09/2030")
    }

    @Test fun walletAcceptsPastedFormattedAndUnformattedExpiry() {
        showWallet(filled = true)
        field.performTextInput("09/2030")
        assertExpiry("09/2030")
        field.performTextReplacement("0731")
        assertExpiry("07/31")
    }

    @Test fun filteredPasteAndLengthLimitKeepCursorInBounds() {
        showWallet(filled = false)
        field.performTextInput(" 09 / 2030 extra 99")
        assertExpiry("09/2030")
    }

    @Test fun selectionReplacementAndEditingMonthKeepTheirPositions() {
        showWallet(filled = true)
        field.performTextInput("09/2030")
        field.performTextInputSelection(TextRange(3, 7))
        field.performTextInput("31")
        assertExpiry("09/31")
        field.performTextInputSelection(TextRange(1, 2))
        field.performTextInput("7")
        assertExpiry("07/31", 2)
        field.performTextInputSelection(TextRange(4, 3))
        field.performTextInput("2")
        assertExpiry("07/21", 4)
    }

    @Test fun backspaceThroughYearAndSeparatorCanContinueTyping() {
        showWallet(filled = true)
        "0731".forEach { field.performTextInput(it.toString()) }
        field.performKeyInput { pressKey(Key.Backspace) }
        assertExpiry("07/3")
        field.performKeyInput { pressKey(Key.Backspace) }
        assertExpiry("07")
        field.performTextInput("2")
        assertExpiry("07/2")
        field.performTextInput("9")
        assertExpiry("07/29")
        field.performTextInputSelection(TextRange(3))
        field.performKeyInput { pressKey(Key.Backspace) }
        assertExpiry("07/29", 2)
        field.performKeyInput { pressKey(Key.Backspace) }
        assertExpiry("02/9", 1)
    }

    @Test fun passwordPaymentFieldRetainsTwoDigitYearLimit() {
        var expiry by mutableStateOf("")
        setExpiryContent { MaterialTheme {
            PaymentExpiryTextField(expiry, { expiry = it },
                Modifier.testTag("entry_payment_expiry"), maxYearDigits = 2)
        } }
        "0731".forEach { field.performTextInput(it.toString()) }
        assertExpiry("07/31")
        field.performTextInput("9")
        assertExpiry("07/31")
        compose.runOnIdle { assertEquals("07/31", expiry) }
    }

    @Test fun externallyLoadedExpiryIsDisplayedWithoutNormalizingOrSaving() {
        var expiry by mutableStateOf("09/2030")
        val changes = mutableListOf<String>()
        setExpiryContent { MaterialTheme {
            PaymentExpiryTextField(expiry, { changes += it; expiry = it },
                Modifier.testTag("entry_payment_expiry"))
        } }
        assertExpiry("09/2030")
        compose.runOnIdle { expiry = "legacy expiry" }
        assertExpiry("legacy expiry")
        compose.runOnIdle { assertEquals(emptyList<String>(), changes) }
    }

    @Test fun recreatedHostRestoresInputAndCursorAndAcceptsFurtherEdits() {
        showWallet(filled = true)
        field.performTextInput("07/3")
        field.performTextInputSelection(TextRange(3))
        assertExpiry("07/3", 3)
        val previousHost = compose.activity

        compose.activityRule.scenario.recreate()
        awaitExpiryField()

        assertNotSame(previousHost, compose.activity)
        assertExpiry("07/3", 3)
        field.performTextInput("1")
        assertExpiry("07/13", 4)
    }

}

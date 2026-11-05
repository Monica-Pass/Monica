package takagi.ru.monica.bitwarden

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import takagi.ru.monica.ui.components.OutlinedTextField

/** A lifecycle reset can coalesce with the final input before the parent recomposes. */
class BitwardenSensitiveInputResetInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun outlinedSecretClearsWhenInputAndExternalResetShareOneFrame() = reset(false)
    @Test fun filledSecretClearsWhenInputAndExternalResetShareOneFrame() = reset(true)

    private fun reset(filled: Boolean) {
        val value = mutableStateOf("")
        val edits = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFilledEntryForm provides filled) {
                    OutlinedTextField(value.value, { value.value = it; edits += it },
                        modifier = Modifier.testTag("sensitive"), saveTextState = false,
                        visualTransformation = PasswordVisualTransformation())
                }
            }
        }
        val setText = compose.onNodeWithTag("sensitive").fetchSemanticsNode()
            .config[SemanticsActions.SetText].action!!
        compose.runOnIdle {
            assertTrue(setText(AnnotatedString("Synthetic unsent secret")))
            assertEquals("Synthetic unsent secret", value.value)
            value.value = ""
        }
        compose.waitForIdle()
        assertEquals("", value.value)
        val node = compose.onNodeWithTag("sensitive").fetchSemanticsNode()
        assertEquals("", node.config[SemanticsProperties.InputText].text)
        assertEquals("", node.config[SemanticsProperties.EditableText].text)
        assertEquals(listOf("Synthetic unsent secret"), edits)
        compose.runOnIdle { value.value = "abcdef" }
        val select = compose.onNodeWithTag("sensitive").fetchSemanticsNode()
            .config[SemanticsActions.SetSelection].action!!
        compose.runOnIdle { assertTrue(select(2, 5, false)) }
        assertEquals(TextRange(2, 5), compose.onNodeWithTag("sensitive").fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { value.value = "abc" }
        assertEquals(TextRange(2, 3), compose.onNodeWithTag("sensitive").fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange])
        assertEquals("abc", compose.onNodeWithTag("sensitive").fetchSemanticsNode()
            .config[SemanticsProperties.InputText].text)
        assertEquals("External resets and cursor moves must not emit input", listOf("Synthetic unsent secret"), edits)
    }
}

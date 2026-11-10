package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.suggestions.CommonInfoTestActivity

@RunWith(AndroidJUnit4::class)
class ConnectedFieldBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()

    @After fun clearContent() { showContent {} }

    private fun showContent(content: @Composable () -> Unit) {
        // MuMu may recreate the activity for an orientation/configuration update after launch.
        // This debug host reinstalls its current composition from onCreate.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = content
        }
        compose.waitForIdle()
    }

    @Test fun nestedGroupsKeepOnlyOuterCornersAndBothValueOverloadsKeepEditsAndSelection() {
        var first by mutableStateOf("Synthetic first")
        var second by mutableStateOf(TextFieldValue("Synthetic second"))
        var third by mutableStateOf("Synthetic third")
        var firstChanges = 0
        showContent {
            Page {
                TemplateFormSection("") {
                    ConnectedFieldGroup(
                        { OutlinedTextField(first, { first = it; firstChanges++ },
                            modifier = Modifier.fillMaxWidth().testTag("first"), singleLine = true) },
                        { ConnectedFieldGroup(
                            { OutlinedTextField(second, { second = it },
                                modifier = Modifier.fillMaxWidth().testTag("second"), singleLine = true) },
                            { OutlinedTextField(third, { third = it },
                                modifier = Modifier.fillMaxWidth().testTag("third"), singleLine = true) },
                        ) },
                    )
                }
            }
        }
        assertThreeRowPixels()
        val firstNode = compose.onNodeWithTag("first")
        firstNode.performTextReplacement("abcdef")
        firstNode.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 3, false) }
        firstNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 3)))
        compose.runOnIdle { assertEquals("abcdef", first); assertEquals(1, firstChanges) }
        compose.onNodeWithTag("second").performTextReplacement("second replacement")
        compose.onNodeWithTag("second").performSemanticsAction(SemanticsActions.SetSelection) { it(2, 6, false) }
        compose.runOnIdle {
            assertEquals("second replacement", second.text)
            assertEquals(TextRange(2, 6), second.selection)
            // Simulate an authoritative parent reset while a selection exists.
            first = "x"
        }
        firstNode.assertTextEquals("x")
        firstNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 1)))
        compose.runOnIdle { assertEquals(1, firstChanges); assertEquals("Synthetic third", third) }
    }

    @Test fun groupedErrorSupportAndIndependentButtonRemainWholeAt320DpWithLargeRtlText() {
        var clicks = 0
        showContent {
            Page(largeRtl = true) {
                TemplateFormSection("") {
                    ConnectedFieldGroup(
                        { OutlinedTextField("Synthetic", {}, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("first")) },
                        { OutlinedTextField(TextFieldValue(""), {}, isError = true,
                            modifier = Modifier.fillMaxWidth().testTag("error"),
                            label = { Text(stringResource(R.string.notes_optional), Modifier.testTag("label")) },
                            supportingText = { Text(stringResource(R.string.api_key_required) + "\n" +
                                stringResource(R.string.barcode_manual_input_support), Modifier.testTag("support")) }) },
                    )
                    FilledTonalButton(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("independent")) {
                        Text(stringResource(R.string.wifi_scan_qr))
                    }
                }
            }
        }
        capture("error-large-rtl").recycle()
        compose.onNodeWithTag(ROOT).assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("error").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        for (tag in listOf("label", "support")) {
            val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
            target.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$tag exposes actual measured text", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(1.5f, layout.layoutInput.density.fontScale, 0f)
                assertEquals(LayoutDirection.Rtl, layout.layoutInput.layoutDirection)
                assertFalse("$tag keeps every requested line", layout.multiParagraph.didExceedMaxLines)
                repeat(layout.lineCount) { assertFalse("$tag must not ellipsize line $it", layout.isLineEllipsized(it)) }
            }
            val node = target.fetchSemanticsNode()
            assertEquals("$tag remains vertically visible", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
            assertEquals("$tag remains horizontally visible", node.size.width.toFloat(), node.boundsInRoot.width, 1f)
        }
        val support = compose.onNodeWithTag("support", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("independent")
        assertTrue("Independent action follows all supporting text", button.fetchSemanticsNode().boundsInRoot.top > support.bottom)
        button.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Composable private fun Page(largeRtl: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        val current = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(current.density, if (largeRtl) 1.5f else 1f),
            LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White,
                surfaceContainer = FIELD, surfaceContainerHigh = FIELD)) {
                Surface(Modifier.width(320.dp).testTag(ROOT), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
                }
            }
        }
    }

    private fun assertThreeRowPixels() {
        val image = capture("nested-overloads")
        try {
            val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
            val density = image.width / 320f
            val bounds = listOf("first", "second", "third").map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            bounds.forEachIndexed { index, b ->
                for (top in listOf(true, false)) for (start in listOf(true, false)) {
                    val x = ((if (start) b.left + 6f * density else b.right - 6f * density) - root.left).roundToInt()
                    val y = ((if (top) b.top + 6f * density else b.bottom - 6f * density) - root.top).roundToInt()
                    val outer = if (top) index == 0 else index == bounds.lastIndex
                    val expected = (if (outer) Color.White else FIELD).toArgb()
                    val actual = image.getPixel(x, y)
                    val difference = maxOf(abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)),
                        abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)),
                        abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)))
                    assertTrue("Nested row $index top=$top left=$start outer=$outer keeps its own correct background", difference <= 12)
                }
                if (index > 0) assertEquals("Nested group boundary stays at 2dp", 2f * density,
                    b.top - bounds[index - 1].bottom, 1.1f)
            }
        } finally { image.recycle() }
    }

    private fun capture(name: String): android.graphics.Bitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().also { bitmap ->
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "connected-fields-327")
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    private companion object {
        const val ROOT = "connected-behavior-root"
        val FIELD = Color(0xFF89ABC1)
    }
}

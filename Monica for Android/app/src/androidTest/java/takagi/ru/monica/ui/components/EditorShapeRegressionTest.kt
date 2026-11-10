package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.suggestions.CommonInfoTestActivity

/** Real parent-composited pixels, with synthetic resource text and no account or vault data. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class EditorShapeRegressionTest {
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

    @Test fun selectedBorderedSurfaceKeepsItsEdgesInsideTemplateSection() {
        var dark by mutableStateOf(false)
        showContent {
            TestPage(dark) {
                TemplateFormSection("") { AccountSurface("grouped") }
                Spacer(Modifier.height(24.dp))
                AccountSurface("reference")
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.waitForIdle()
            compose.onNodeWithTag("grouped").assertIsSelected()
            compareParentCompositedEdges("surface-${if (night) "dark" else "light"}")
        }
    }

    @Test fun filterChipKeepsItsLeadingOutlineInsideTemplateSection() {
        var dark by mutableStateOf(false)
        showContent {
            TestPage(dark) {
                TemplateFormSection("") { ChipRow("grouped") }
                Spacer(Modifier.height(24.dp))
                ChipRow("reference")
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.waitForIdle()
            compareParentCompositedEdges("chip-${if (night) "dark" else "light"}")
        }
    }

    @Test fun filledErrorFieldKeepsLocalizedTextAndErrorSemanticsAt320DpAndLargeFont() {
        showSupportingField(enabled = true, error = true)
        capturePage("field-error-large").recycle()
        compose.onNodeWithTag(ROOT).assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("field").assertIsEnabled()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertCompleteText("field")
        assertCompleteText("field-label")
        assertCompleteText("field-support")
    }

    @Test fun filledDisabledFieldKeepsLocalizedSupportingTextAt320DpAndLargeFont() {
        showSupportingField(enabled = false, error = false)
        capturePage("field-disabled-large").recycle()
        compose.onNodeWithTag(ROOT).assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("field").assertIsNotEnabled()
        assertFalse(compose.onNodeWithTag("field").fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        assertCompleteText("field")
        assertCompleteText("field-label")
        assertCompleteText("field-support")
    }

    @Composable
    private fun TestPage(dark: Boolean, fontScale: Float = 1f, content: @Composable ColumnScope.() -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            // A fixed palette keeps the reference independent of dynamic wallpaper/theme state.
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.width(320.dp).testTag(ROOT), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
                }
            }
        }
    }

    @Composable
    private fun AccountSurface(tag: String) {
        // These are the same Surface and outline in both positions, not a hand-built expected mask.
        Surface(onClick = {}, modifier = Modifier.fillMaxWidth().height(72.dp).testTag(tag)
            .semantics { selected = true }, shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.send_account_section_title))
            }
        }
    }

    @Composable
    private fun ChipRow(tag: String) {
        Row(Modifier.fillMaxWidth()) {
            FilterChip(selected = false, onClick = {}, modifier = Modifier.testTag(tag),
                label = { Text(stringResource(R.string.send_type_file)) })
        }
    }

    private fun showSupportingField(enabled: Boolean, error: Boolean) {
        showContent {
            TestPage(dark = error, fontScale = 1.5f) {
                TemplateFormSection("") {
                    val hint = stringResource(R.string.barcode_manual_input_hint)
                    val support = stringResource(R.string.barcode_manual_input_support)
                    OutlinedTextField(value = hint, onValueChange = {}, saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("field"), enabled = enabled,
                        isError = error, minLines = 2,
                        label = { Text(stringResource(R.string.notes_optional), Modifier.testTag("field-label")) },
                        supportingText = {
                            Text(if (error) stringResource(R.string.api_key_required) + "\n" + support else support,
                                Modifier.testTag("field-support"))
                        })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCompleteText(tag: String) {
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
        target.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        recordTextLayoutDiagnostic(tag, target.fetchSemanticsNode(), layouts)
        assertTrue("$tag must expose an actual text layout", layouts.isNotEmpty())
        assertTrue("$tag must retain the requested system font scale", layouts.all { it.layoutInput.density.fontScale == 1.5f })
        layouts.forEach { assertActualTextFits(it, tag) }
        val node = target.fetchSemanticsNode()
        assertEquals("$tag must not be vertically clipped by the page", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        assertEquals("$tag must not be horizontally clipped by the page", node.size.width.toFloat(), node.boundsInRoot.width, 1f)
    }

    private fun assertActualTextFits(layout: TextLayoutResult, label: String) {
        val value = layout.layoutInput.text.text
        assertTrue("$label must contain measured text", value.isNotEmpty() && layout.lineCount > 0)
        assertFalse("$label must not drop lines", layout.multiParagraph.didExceedMaxLines)
        // A minimized M3 label may retain the parent's paragraph constraint width after its
        // measured node shrinks. Verify actual lines/characters, not that unused paragraph width.
        fun inside(left: Float, top: Float, right: Float, bottom: Float, part: String) {
            assertTrue("$label $part [$left,$top,$right,$bottom] exceeds ${layout.size}",
                left >= -1f && top >= -1f && right <= layout.size.width + 1f && bottom <= layout.size.height + 1f)
        }
        repeat(layout.lineCount) { line ->
            assertFalse("$label line $line must not ellipsize", layout.isLineEllipsized(line))
            inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line),
                layout.getLineBottom(line), "line $line")
        }
        value.indices.filter { !value[it].isWhitespace() }.forEach { offset ->
            val box = layout.getBoundingBox(offset)
            inside(box.left, box.top, box.right, box.bottom, "character $offset")
        }
    }

    private fun recordTextLayoutDiagnostic(tag: String, node: androidx.compose.ui.semantics.SemanticsNode,
        layouts: List<TextLayoutResult>) {
        val detail = buildString {
            appendLine("time=${System.currentTimeMillis()} tag=$tag layouts=${layouts.size}")
            appendLine("nodeSize=${node.size} nodeBounds=${node.boundsInRoot} windowBounds=${node.boundsInWindow}")
            layouts.forEachIndexed { index, layout ->
                val input = layout.layoutInput
                appendLine("layout=$index text=${input.text.text} fontSize=${input.style.fontSize} lineHeight=${input.style.lineHeight}")
                appendLine("density=${input.density.density} fontScale=${input.density.fontScale} style=${input.style}")
                appendLine("constraints=${input.constraints} size=${layout.size} paragraphWidth=${layout.multiParagraph.width} paragraphHeight=${layout.multiParagraph.height}")
                appendLine("overflow=${layout.hasVisualOverflow} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} exceededMaxLines=${layout.multiParagraph.didExceedMaxLines} maxLines=${input.maxLines} overflowMode=${input.overflow}")
                appendLine("firstBaseline=${layout.firstBaseline} lastBaseline=${layout.lastBaseline} lines=${layout.lineCount}")
                repeat(layout.lineCount) { line ->
                    appendLine("line=$line top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} baseline=${layout.getLineBaseline(line)} left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} start=${layout.getLineStart(line)} end=${layout.getLineEnd(line)} visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} ellipsized=${layout.isLineEllipsized(line)}")
                }
                input.text.text.indices.filter { !input.text.text[it].isWhitespace() }.forEach { offset ->
                    appendLine("char=$offset box=${layout.getBoundingBox(offset)}")
                }
            }
        }
        android.util.Log.i("EditorShapeLayout", detail)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "editor-shapes-325")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "layout-$tag.txt").appendText(detail + "\n")
    }

    private fun compareParentCompositedEdges(name: String) {
        val page = capturePage(name)
        try {
            val grouped = crop(page, "grouped")
            val reference = crop(page, "reference")
            try {
                save(grouped, "$name-grouped")
                save(reference, "$name-reference")
                assertEquals("Same component width", reference.width, grouped.width)
                assertEquals("Same component height", reference.height, grouped.height)
                val width = reference.width
                val height = reference.height
                val edgeBand = max(2, min(width, height) / 3)
                var samples = 0
                var different = 0
                for (y in 0 until height) for (x in 0 until width) {
                    if (x >= edgeBand && x < width - edgeBand && y >= edgeBand && y < height - edgeBand) continue
                    samples++
                    if (channelDistance(grouped.getPixel(x, y), reference.getPixel(x, y)) > 12) different++
                }
                // Permit a few rasterization pixels, not a missing arc or a flattened outline.
                val allowance = max(4, samples / 1000)
                assertTrue("$name: parent changed $different of $samples edge pixels (allowance $allowance)", different <= allowance)
            } finally { grouped.recycle(); reference.recycle() }
        } finally { page.recycle() }
    }

    private fun channelDistance(a: Int, b: Int): Int = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
        abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b)))

    /** Capture the containing page: capturing a child's layer alone could omit its ancestor clip. */
    private fun capturePage(name: String): Bitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().also { save(it, name) }
    }

    private fun crop(page: Bitmap, tag: String): Bitmap {
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val left = (bounds.left - root.left).roundToInt()
        val top = (bounds.top - root.top).roundToInt()
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        check(left >= 0 && top >= 0 && left + width <= page.width && top + height <= page.height)
        return Bitmap.createBitmap(page, left, top, width, height)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "editor-shapes-325")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private companion object { const val ROOT = "editor-shape-root" }
}

package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.components.LocalExpansionAnimationsEnabled
import takagi.ru.monica.ui.screens.ResultCard

class GeneratorResultMotionTest {
    @get:Rule val compose = createComposeRule()
    private var result by mutableStateOf(LONG)
    private var compact by mutableFloatStateOf(0f)
    private var motion by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private val evidence = StringBuilder()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(largeDark: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalExpansionAnimationsEnabled provides motion,
                LocalDensity provides Density(density, if (largeDark) 1.5f else 1f)) {
                MaterialTheme(colorScheme = if (largeDark) androidx.compose.material3.darkColorScheme()
                    else androidx.compose.material3.lightColorScheme()) {
                    Column(Modifier.width(320.dp).padding(16.dp)) {
                        if (visible) ResultCard(result, compactProgress = compact, onCopy = {})
                        Text("Following settings", Modifier.testTag("following"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun height() = compose.onNodeWithTag("generator_result").fetchSemanticsNode().boundsInRoot.height
    private fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "generator-motion-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun resize(largeDark: Boolean) {
        show(largeDark)
        val longHeight = height()
        capture("$largeDark-expanded")
        compose.runOnIdle { result = "Example-1234" }
        val heights = mutableListOf<Float>()
        repeat(10) { frame ->
            advance(32)
            val card = compose.onNodeWithTag("generator_result").fetchSemanticsNode().boundsInRoot
            heights += card.height
            assertEquals(card.bottom, compose.onNodeWithTag("following").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.onNodeWithText(LONG).assertDoesNotExist()
            if (frame == 2) capture("$largeDark-shrinking")
        }
        advance(1_000)
        val shortHeight = height()
        capture("$largeDark-collapsed")
        evidence.appendLine("$largeDark collapse $longHeight -> $shortHeight: $heights")
        File(context.filesDir, "generator-motion-$largeDark.txt").writeText(evidence.toString())
        assertTrue("Collapse needs measured intermediate heights: $heights", heights.any { it > shortHeight + 1 && it < longHeight - 1 })
        assertTrue(heights.zipWithNext().all { (a, b) -> b <= a + 1 })
        compose.runOnIdle { result = LONG }
        advance(96)
        assertTrue("Expansion must also be smooth", height() > shortHeight && height() < longHeight)
        compose.runOnIdle { result = "Example-1234" }
        val interrupted = height()
        advance(32)
        assertTrue("Reversal must continue from the current size", height() >= shortHeight && height() < longHeight)
        assertTrue(interrupted < longHeight)
        advance(1_000)
        assertEquals(shortHeight, height(), 1f)
    }
    @Test fun longAndShortResultsResizeSmoothly() = resize(false)
    @Test fun largeTextDarkResultsResizeSmoothly() = resize(true)

    @Test fun stickyCompactTransitionHasIntermediateHeight() {
        show()
        val expanded = height()
        compose.runOnIdle { compact = 1f }
        advance(96)
        val middle = height()
        advance(1_000)
        val collapsed = height()
        assertTrue("Sticky compact must shrink smoothly", middle > collapsed && middle < expanded)
        compose.runOnIdle { compact = 0f }
        advance(96)
        assertTrue(height() > collapsed && height() < expanded)
    }

    @Test fun reducedMotionAndRemovalAreImmediate() {
        motion = false
        show()
        val expanded = height()
        compose.runOnIdle { result = "Example-1234" }
        advance(32)
        val small = height()
        assertTrue(small < expanded)
        advance(96)
        assertEquals(small, height(), 1f)
        // Attach the animator at the current measured size before requesting a new size.
        // Enabling motion and changing content in the same frame has no previous animated size.
        compose.runOnIdle { motion = true }
        advance(32)
        compose.runOnIdle { result = LONG }
        advance(96)
        assertTrue(height() < expanded)
        compose.runOnIdle { motion = false }
        advance(32)
        assertEquals(expanded, height(), 1f)
        compose.runOnIdle { motion = true }
        advance(32)
        compose.runOnIdle { result = "Example-1234" }
        advance(32)
        compose.runOnIdle { visible = false }
        advance(32)
        compose.onNodeWithTag("generator_result").assertDoesNotExist()
        compose.onNodeWithText("Example-1234").assertDoesNotExist()
    }

    private companion object { const val LONG = "Example-Only-1234567890\nExample-Only-abcdefghij\nExample-Only-2468135790\nExample-Only-klmnopqrst\nExample-Only-9753186420\nExample-Only-uvwxyzabcd" }
}

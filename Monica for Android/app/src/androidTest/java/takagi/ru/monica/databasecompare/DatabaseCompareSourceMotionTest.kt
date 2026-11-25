package takagi.ru.monica.databasecompare

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.screens.CompareSourceCard
import takagi.ru.monica.ui.theme.MonicaTheme

class DatabaseCompareSourceMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun verifyMotion(dark: Boolean, scale: Float) {
        val authenticated = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp)) {
                        CompareSourceCard(true, DatabaseCompareSource.Registered(CompareFormat.KDBX, 91, "Synthetic vault"),
                            "synthetic-input", null, authenticated.value, false, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("compare_left_password").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        fun height() = compose.onNodeWithTag("compare_left_card").fetchSemanticsNode().boundsInRoot.height
        fun capture(name: String) {
            val image = compose.onNodeWithTag("compare_left_card").captureToImage().asAndroidBitmap()
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "compare-ux-${if (dark) "dark" else "light"}-$scale-$name.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val expanded = height()
        capture("expanded")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { authenticated.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("compare_left_password").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(64)
            val middle = height()
            capture("contracting")
            compose.mainClock.advanceTimeBy(2_000)
            val collapsed = height()
            capture("collapsed")
            assertTrue("Card needs a real intermediate height: $expanded > $middle > $collapsed", expanded > middle && middle > collapsed)
            compose.onNodeWithTag("compare_left_authenticated").assertExists()
            compose.runOnUiThread { authenticated.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val expanding = height()
            assertTrue(expanding > collapsed && expanding < expanded)
            // Interrupt an expansion. The next layout must continue from its current size.
            compose.runOnUiThread { authenticated.value = true }
            compose.mainClock.advanceTimeByFrame()
            val interrupted = height()
            assertTrue("Interruption must not snap to either endpoint", interrupted > collapsed && interrupted < expanded)
            compose.onNodeWithTag("compare_left_password").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(2_000)
            assertEquals(collapsed, height(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun lightCardShrinksSmoothlyAndImmediatelyRemovesPassword() = verifyMotion(false, 1f)
    @Test fun darkLargeTextCardShrinksSmoothlyAndCanReverse() = verifyMotion(true, 1.5f)
}

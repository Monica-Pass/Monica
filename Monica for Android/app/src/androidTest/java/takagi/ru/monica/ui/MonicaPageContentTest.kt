package takagi.ru.monica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.navigation.MonicaPageContent

class MonicaPageContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingPageRemovesOldPasswordFieldBeforeAnimationCompletes() {
        var page by mutableIntStateOf(0)
        var disposed = false
        compose.setContent {
            MonicaPageContent(pageKey = page) {
                val mountedPage = remember { page }
                if (mountedPage == 0) {
                    DisposableEffect(Unit) { onDispose { disposed = true } }
                    TextField("synthetic-password", {}, modifier = Modifier.testTag("old-password"))
                } else Text("Current page")
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { page = 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("old-password").assertDoesNotExist()
        assertTrue("Old content must not survive as an outgoing animated page", disposed)
        compose.mainClock.advanceTimeBy(250)
    }

    @Test fun samePageUpdatesKeepCompositionAndChangingPageDisposesIt() {
        var page by mutableIntStateOf(0)
        var value by mutableStateOf("before")
        var mounted = 0
        var disposed = 0
        compose.setContent {
            MonicaPageContent(pageKey = page) {
                DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                Text(value)
            }
        }
        compose.runOnIdle { value = "after" }
        compose.onNodeWithText("before").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, mounted); assertEquals(0, disposed); page = 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, mounted); assertEquals(1, disposed) }
    }

    @Test fun pageEntryIncludesScaleAndReturnsToFullBounds() {
        var page by mutableIntStateOf(0)
        compose.setContent {
            Box(Modifier.size(240.dp, 160.dp)) {
                MonicaPageContent(pageKey = page, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("page-bounds"))
                }
            }
        }
        val full = compose.onNodeWithTag("page-bounds").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { page = 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val entering = compose.onNodeWithTag("page-bounds").fetchSemanticsNode().boundsInRoot
        assertTrue("Entry must scale, not only fade", entering.width < full.width)
        compose.mainClock.advanceTimeBy(250)
        val settled = compose.onNodeWithTag("page-bounds").fetchSemanticsNode().boundsInRoot
        assertEquals(full.width, settled.width, .1f)
        assertEquals(full.height, settled.height, .1f)
    }
}

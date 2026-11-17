package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.security.SecurityManager

/** Real overview/search rendering plus a controlled late-result boundary. Synthetic titles only. */
@RunWith(AndroidJUnit4::class)
class VaultOverviewSearchMotionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var durationScale by mutableFloatStateOf(1f)
    // The rule supplies its own Recomposer. Control the same context element that the
    // app's WindowRecomposer derives from Android, so the whole class is self-contained.
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = durationScale
    })
    private var locked by mutableStateOf(false)
    private var reduce by mutableStateOf(false)
    private var opened: String? = null
    private val rowTag = "vault_item_password:1"

    @After fun restoreClock() { compose.mainClock.autoAdvance = true }

    private fun showOverview(largeDark: Boolean = false) {
        val security = SecurityManager(context)
        val rows = buildVaultV2PasswordItems((1L..6L).map { id ->
            PasswordEntry(id = id, title = "Motion demo $id", username = "demo@example.test", website = "", password = "")
        })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (largeDark) 1.5f else 1f)) {
                MaterialTheme(colorScheme = if (largeDark) darkColorScheme() else lightColorScheme()) {
                    val sources = listOf(VaultOverviewSource("local", "Local", "Monica", locked = locked))
                    val config = remember { VaultOverviewConfig() }
                    val snapshot = remember(sources) {
                        buildVaultOverviewSnapshot(rows, sources, "local", config, emptyMap(), emptyMap(),
                            emptyList(), emptyList(), emptyMap(), emptyList(), aggregate = ::aggregateVaultOverviewKotlin)
                    }
                    Surface(Modifier.fillMaxSize()) {
                    VaultOverviewScreen(snapshot, sources, emptyList(), emptyList(), emptyList(), "local", config,
                        rememberLazyListState(), securityManager = security, reduceAnimations = reduce, trashCount = 0,
                        onConfigChange = {}, onSelectScope = {}, onOpenSource = {}, onOpenItem = { opened = it.key },
                        onOpenType = {}, onOpenFolder = {}, onFavorites = {}, onArchive = {}, onTrash = {},
                        onAllItems = {}, onSearch = {}, onScanFidoQr = {}, onUnlock = {},
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding())
                    }
                }
            }
        }
        compose.onNodeWithTag("overview_search").assertIsDisplayed()
    }

    private fun openAtFirstResult() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("overview_search").performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun top() = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.top
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "search-motion-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun searchSlidesQuicklyThenTypingDoesNotReplayAndCloseRemovesRows() {
        showOverview()
        openAtFirstResult()
        val initial = top()
        compose.mainClock.advanceTimeBy(64)
        val middle = top()
        capture("light-middle")
        compose.mainClock.advanceTimeBy(192)
        val settled = top()
        capture("light-settled")
        assertTrue("Rows must rise from below: $initial -> $middle -> $settled", initial > middle && middle > settled)
        compose.mainClock.advanceTimeBy(160)
        assertEquals("Entrance must already be settled within 256ms", settled, top(), 0.5f)
        compose.onNode(hasSetTextAction()).performTextReplacement("demo 1")
        compose.mainClock.advanceTimeBy(96)
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("vault_item_password:2").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("Typing must not replay the entrance", settled, top(), 0.5f)
        compose.onNodeWithTag(rowTag).performClick()
        compose.runOnIdle { assertEquals("password:1", opened) }
        compose.onNodeWithContentDescription(context.getString(R.string.topbar_close_search)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("overview_search_results").assertDoesNotExist()
        compose.onNodeWithTag(rowTag).assertDoesNotExist()
        // A new search opening has its own entrance.
        compose.mainClock.advanceTimeBy(300)
        openAtFirstResult()
        assertTrue("Reopening must enter from below again", top() > settled + 1f)
    }

    @Test fun lockingDuringEntranceRemovesRowsImmediatelyAtLargeFontInDarkTheme() {
        showOverview(largeDark = true)
        openAtFirstResult()
        compose.mainClock.advanceTimeBy(64)
        capture("dark-large-middle")
        compose.runOnUiThread { locked = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(rowTag).assertDoesNotExist()
        compose.onNodeWithTag("overview_search_results").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.vault_overview_locked)).assertIsDisplayed()
        capture("dark-large-locked")
    }

    @Test fun reducedMotionHasNoTranslatedFirstResult() {
        reduce = true
        showOverview(largeDark = true)
        openAtFirstResult()
        val first = top()
        compose.mainClock.advanceTimeBy(256)
        assertEquals(first, top(), 0.5f)
        compose.onNodeWithTag(rowTag).assertIsDisplayed()
        capture("dark-large-reduced")
    }

    @Test fun delayedReadinessStartsEntranceAndLaterRefreshDoesNotReplay() {
        var ready by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.padding(top = 48.dp).then(rememberOverviewSearchEntrance(ready, reduce))) {
                    if (ready) Box(Modifier.size(80.dp).background(Color.Blue).testTag("ready"))
                    else Box(Modifier.size(80.dp).background(Color.Gray).testTag("loading"))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("loading").assertIsDisplayed()
        compose.runOnUiThread { ready = true }
        compose.mainClock.advanceTimeByFrame()
        fun readyTop() = compose.onNodeWithTag("ready").fetchSemanticsNode().boundsInRoot.top
        val first = readyTop()
        compose.mainClock.advanceTimeBy(64)
        val middle = readyTop()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val color = image.getPixel(with(compose.density) { 40.dp.roundToPx() }, middle.toInt() + with(compose.density) { 40.dp.roundToPx() })
        image.recycle()
        assertTrue("Intermediate frame must blend blue with the white background", android.graphics.Color.red(color) in 1..254)
        compose.mainClock.advanceTimeBy(192)
        val end = readyTop()
        assertTrue("Slow data must still animate when ready", first > middle && middle > end)
        compose.runOnUiThread { ready = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("ready").assertDoesNotExist()
        compose.runOnUiThread { ready = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Refreshing results must not restart", end, readyTop(), 0.5f)
    }

    @Test fun systemDisabledMotionSettlesWithoutTimedFrames() {
        durationScale = 0f
        showOverview()
        openAtFirstResult()
        // Zero-scale animations complete on the next animation frame, without a timed tween.
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val first = top()
        compose.mainClock.advanceTimeBy(256)
        assertEquals(first, top(), 0.5f)
        capture("system-disabled")
    }
}

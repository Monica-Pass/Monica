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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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

/** Drives the real overview/search transition with synthetic entries. */
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

    @Test fun searchRisesAndOverviewReturnsInPlaceWithSteamMotion() {
        showOverview()
        fun overviewWidth() = compose.onNodeWithTag("overview_modules").fetchSemanticsNode().boundsInRoot.width
        val overviewWidthAtRest = overviewWidth()

        compose.mainClock.autoAdvance = false
        openAtFirstResult()
        val initial = top()
        compose.mainClock.advanceTimeBy(64)
        val middle = top()
        capture("light-middle")
        compose.mainClock.advanceTimeBy(240)
        val settled = top()
        assertTrue("Search should rise: $initial -> $middle -> $settled", initial > middle && middle > settled)
        capture("light-settled")
        compose.onNodeWithTag(rowTag).performClick()
        compose.runOnIdle { assertEquals("password:1", opened) }

        compose.onNodeWithContentDescription(context.getString(R.string.topbar_close_search)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("overview_search_results").assertDoesNotExist()
        compose.onNodeWithTag(rowTag).assertDoesNotExist()
        val returning = overviewWidth()
        assertTrue("Overview must return with a slight scale-in", returning < overviewWidthAtRest)
        compose.mainClock.advanceTimeBy(64)
        val returnMiddle = overviewWidth()
        assertTrue("Returning overview should grow: $returning -> $returnMiddle", returnMiddle > returning)
        capture("return-middle")
        compose.mainClock.advanceTimeBy(240)
        assertEquals(overviewWidthAtRest, overviewWidth(), 0.5f)
        capture("return-settled")
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

    @Test fun closeAndReopenDuringMotionDoesNotKeepOldQueryOrRows() {
        showOverview()
        openAtFirstResult()
        compose.mainClock.advanceTimeBy(230)
        compose.onNode(hasSetTextAction()).performTextReplacement("absent")
        compose.onNodeWithContentDescription(context.getString(R.string.topbar_close_search)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("overview_search_results").assertDoesNotExist()
        // Interrupt the overview entrance with another explicit search opening.
        compose.onNodeWithTag("overview_search").performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(rowTag).assertIsDisplayed()
        compose.onNodeWithText("absent").assertDoesNotExist()
    }

    @Test fun filteringMovesTheSurvivingRowAndClearingRestoresMatches() {
        showOverview()
        openAtFirstResult()
        compose.mainClock.advanceTimeBy(420)
        val target = compose.onNodeWithTag("vault_item_password:2")
        val startY = target.fetchSemanticsNode().boundsInRoot.top
        val endY = top()
        compose.onNode(hasSetTextAction()).performTextReplacement("demo 2")
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            val y = target.fetchSemanticsNode().boundsInRoot.top
            y < startY - 1f && y > endY + 1f
        }
        capture("filter-moving")
        compose.mainClock.advanceTimeBy(800)
        assertEquals(endY, target.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNode(hasSetTextAction()).performTextReplacement("absent")
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText(context.getString(R.string.no_results)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("demo 4")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag(rowTag).assertIsDisplayed()
        target.assertIsDisplayed()
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

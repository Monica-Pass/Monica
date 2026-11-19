package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.SecurityAnalysisData
import takagi.ru.monica.ui.theme.MonicaTheme

class SecurityScoreIndicatorTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(SecurityAnalysisData())
    private lateinit var localized: android.content.Context
    private var refreshes = 0
    private var backs = 0

    private fun show(dark: Boolean = true, scale: Float = 1f, width: Int = 390, english: Boolean = false) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)
            ) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(width.dp).fillMaxHeight().testTag("security-score-page")) {
                        SecurityAnalysisScreen(state.value, false, { refreshes++ }, {}, { backs++ }, {}, {})
                    }
                }
            }
        }
    }

    private fun capture(name: String, tag: String = "security-score-page"): Bitmap {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(context.filesDir, "security-score-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    @Test fun pendingChecksAnimateWithoutClaimingCompletionAndFinishedScoreStops() {
        // The real model uses 100 while asynchronous breach checks can still be pending.
        state.value = SecurityAnalysisData(securityScore = 86, isAnalyzing = true, analysisProgress = 100)
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(100)
        val indicator = compose.onNodeWithTag("security-score-indicator")
        indicator.assertTextEquals("…").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        )
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.onNodeWithText("100%").assertDoesNotExist()
        val originalBounds = compose.onNodeWithTag("security-overview").getUnclippedBoundsInRoot()
        val first = capture("busy-frame-a", "security-score-indicator")
        compose.mainClock.advanceTimeBy(350)
        val second = capture("busy-frame-b", "security-score-indicator")
        assertFalse("The active graphic should visibly morph", first.sameAs(second))
        first.recycle(); second.recycle()
        capture("busy-dark").recycle()
        compose.runOnIdle { state.value = state.value.copy(isAnalyzing = false) }
        compose.mainClock.advanceTimeBy(600)
        indicator.assertTextEquals("86")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        assertEquals(originalBounds, compose.onNodeWithTag("security-overview").getUnclippedBoundsInRoot())
        val stopped = capture("finished-frame-a", "security-score-indicator")
        compose.mainClock.advanceTimeBy(500)
        val later = capture("finished-frame-b", "security-score-indicator")
        assertTrue("The finished score should be stationary", stopped.sameAs(later))
        stopped.recycle(); later.recycle()
        capture("finished-dark").recycle()
    }

    @Test fun narrowDoubleTextKeepsFullScoreAndSummaryInsideCard() {
        state.value = SecurityAnalysisData(securityScore = 100)
        show(dark = false, scale = 2f, width = 320)
        val indicator = compose.onNodeWithTag("security-score-indicator")
        indicator.assertTextEquals("100").assertIsDisplayed()
        val card = compose.onNodeWithTag("security-overview").getUnclippedBoundsInRoot()
        val badge = indicator.getUnclippedBoundsInRoot()
        val summary = compose.onNodeWithTag("security-score-summary").getUnclippedBoundsInRoot()
        assertTrue(summary.top >= badge.bottom)
        assertTrue(summary.left >= card.left && summary.right <= card.right && summary.bottom <= card.bottom)
        compose.onNodeWithText(localized.getString(R.string.security_score_out_of_100)).assertIsDisplayed()
        val texts = compose.onAllNodes(hasText("100", substring = false), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, texts.size)
        val scoreBounds = texts.single().boundsInRoot
        val badgeBounds = indicator.fetchSemanticsNode().boundsInRoot
        assertTrue(scoreBounds.left >= badgeBounds.left && scoreBounds.right <= badgeBounds.right)
        capture("large-light-320").recycle()
    }

    @Test fun scoreStatesRemainReadableInEnglishAndActionsStillWork() {
        show(dark = false, english = true, width = 360)
        listOf(86 to R.string.security_score_good, 65 to R.string.security_score_fair,
            12 to R.string.security_score_needs_attention).forEach { (score, label) ->
            compose.runOnIdle { state.value = state.value.copy(securityScore = score) }
            compose.onNodeWithTag("security-score-indicator").assertTextEquals(score.toString())
            compose.onNodeWithText(localized.getString(label)).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(localized.getString(R.string.refresh)).performClick()
        compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
        assertEquals(1, refreshes)
        assertEquals(1, backs)
        capture("english-low-score").recycle()
    }

    @Test fun unknownProgressAndInterruptedAnalysisDoNotLeaveAnActiveIndicator() {
        state.value = SecurityAnalysisData(securityScore = 65, isAnalyzing = true, analysisProgress = 0)
        show(scale = 1.5f, width = 320)
        compose.onNodeWithTag("security-score-indicator").assertTextEquals("…")
        capture("busy-large-320").recycle()
        compose.runOnIdle { state.value = state.value.copy(isAnalyzing = false, error = "Synthetic analysis interruption") }
        compose.onNodeWithTag("security-score-indicator").assertTextEquals("65")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithText("Synthetic analysis interruption").assertIsDisplayed()
    }
}

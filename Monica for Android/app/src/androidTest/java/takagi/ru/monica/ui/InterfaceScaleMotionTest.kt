package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.LocalExpansionAnimationsEnabled
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsSubpageRow
import takagi.ru.monica.ui.scale.ProvideMonicaInterfaceScale
import takagi.ru.monica.ui.screens.InterfaceScaleSelectionSheet
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.ui.screens.SettingsItem
import takagi.ru.monica.ui.screens.SettingsItemWithSwitch

/** Measures real layout frames, retained composition, and the production scale sheet. */
class InterfaceScaleMotionTest {
    private var durationScale by mutableFloatStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = durationScale
    })
    private var percent by mutableIntStateOf(100)
    private var motion by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var sheet by mutableStateOf(false)
    private var subtitle by mutableStateOf("Example")
    private var checked by mutableStateOf(false)
    private var rowStyle = 0
    private var mounts = 0
    private var saves = 0
    private var base = 1f
    private var density = 1f
    private var fontScale = 1f
    private var followingTop = 0f
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(largeRtl: Boolean = false) {
        compose.setContent {
            base = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(base, if (largeRtl) 1.5f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalExpansionAnimationsEnabled provides motion
            ) {
                Box(Modifier.width(320.dp)) {
                    ProvideMonicaInterfaceScale(percent) {
                        val currentDensity = LocalDensity.current
                        SideEffect { density = currentDensity.density; fontScale = currentDensity.fontScale }
                        DisposableEffect(Unit) { mounts++; onDispose {} }
                        MaterialTheme(colorScheme = if (largeRtl) darkColorScheme() else lightColorScheme()) {
                            Surface {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    Box(Modifier.size(100.dp, 8.dp).testTag("measure"))
                                    if (visible) {
                                        if (rowStyle == 1) {
                                            SettingsPanelRow(Icons.Default.Tune, Setting.INTERFACE_SCALE_TITLE,
                                                subtitle, onClick = { sheet = true }, modifier = Modifier.testTag("card"))
                                        } else if (rowStyle == 2) {
                                            SettingsSubpageRow(Setting.INTERFACE_SCALE_TITLE, subtitle,
                                                Icons.Default.Tune, 0, 2, Modifier.testTag("card"), onClick = { sheet = true })
                                        } else SettingsItem(Icons.Default.Tune, Setting.INTERFACE_SCALE_TITLE,
                                            subtitle = subtitle, onClick = { sheet = true },
                                            modifier = Modifier.testTag("card"))
                                        Box(Modifier.testTag("switch")) {
                                            if (rowStyle == 1) {
                                                SettingsPanelRow(Icons.Default.Tune, Setting.SCREENSHOT_PROTECTION,
                                                    subtitle, checked = checked, onCheckedChange = { checked = it })
                                            } else if (rowStyle == 2) {
                                                SettingsSubpageRow(Setting.SCREENSHOT_PROTECTION, subtitle,
                                                    Icons.Default.Tune, 1, 2, checked = checked, onCheckedChange = { checked = it })
                                            } else SettingsItemWithSwitch(Icons.Default.Tune, Setting.SCREENSHOT_PROTECTION,
                                                subtitle = subtitle, checked = checked, onCheckedChange = { checked = it })
                                        }
                                        Text("Synthetic private content", Modifier.testTag("secret"))
                                    }
                                    Text("Following settings", Modifier.testTag("following")
                                        .onGloballyPositioned { followingTop = it.positionInRoot().y })
                                }
                            }
                            if (sheet) InterfaceScaleSelectionSheet(percent,
                                onPercentChanged = { saves++; percent = it }, onDismiss = { sheet = false })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun width() = compose.onNodeWithTag("measure").fetchSemanticsNode().boundsInRoot.width
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "scale-motion-$rowStyle-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun scaleHasIntermediateFramesAndKeepsTheSameContent() {
        show()
        val start = width()
        compose.runOnIdle { percent = 120 }
        val widths = mutableListOf<Float>()
        repeat(10) { frame ->
            advance(32); widths += width()
            if (frame == 2) capture("density-intermediate")
        }
        advance(1_000)
        val end = width()
        File(context.filesDir, "scale-motion-density.txt").writeText("$start -> $end: $widths")
        assertEquals(start * 1.2f, end, 1f)
        assertTrue("Real intermediate widths: $widths", widths.any { it > start + 1 && it < end - 1 })
        assertTrue(widths.zipWithNext().all { (a, b) -> b >= a - 1f })
        assertEquals(1, mounts)
        assertEquals(1f, fontScale, 0f)
        assertEquals(base * 1.2f, density, .001f)
    }

    @Test fun reverseDuringTransitionContinuesFromCurrentSizeAndResetSettlesExactly() {
        percent = 80
        show()
        val start = width()
        // Measure the normal uninterrupted trajectory before checking a reversal against it.
        compose.runOnIdle { percent = 120 }
        val reference = mutableListOf(start)
        repeat(24) { advance(16); reference += width() }
        compose.runOnIdle { motion = false; percent = 80 }
        advance(32)
        assertEquals(start, width(), 1f)
        compose.runOnIdle { motion = true }
        advance(32)
        compose.runOnIdle { percent = 120 }
        val outward = mutableListOf(start)
        repeat(6) { advance(16); outward += width() }
        val middle = width()
        assertTrue(middle > start && middle < start * 1.5f)
        compose.runOnIdle { percent = 80 }
        val reversal = mutableListOf<Float>()
        repeat(6) { advance(16); reversal += width() }
        File(context.filesDir, "scale-motion-reversal.txt").writeText("reference: $reference, outward: $outward, reverse: $reversal")
        // Retargeting is delivered on the next Compose frame; that frame may finish the old step.
        // Require continuity at that boundary and then decreasing sizes, rather than freezing time.
        val largestStep = reference.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue("outward: $outward, reverse: $reversal",
            (listOf(middle) + reversal).zipWithNext().all { (a, b) -> abs(b - a) <= largestStep + 1f })
        assertTrue(reversal.all { it >= start && it < start * 1.5f })
        assertTrue(reversal.last() < reversal.first())
        advance(1_000)
        assertEquals(start, width(), 1f)
        compose.runOnIdle { percent = 100 }
        advance(1_000)
        assertEquals(start * 1.25f, width(), 1f)
        assertEquals(1, mounts)
    }

    private fun reflow(largeRtl: Boolean) {
        show(largeRtl)
        val small = height("card")
        val smallSwitch = height("switch")
        capture("$largeRtl-before")
        compose.runOnIdle { subtitle = "Example line one\nExample line two\nExample line three\nExample line four" }
        advance(96)
        val mid = height("card")
        val midSwitch = height("switch")
        val following = followingTop
        capture("$largeRtl-reflow")
        advance(1_000)
        val tall = height("card")
        capture("$largeRtl-expanded")
        assertTrue("Card must grow continuously: $small / $mid / $tall", mid > small && mid < tall)
        assertTrue(midSwitch > smallSwitch && midSwitch < height("switch"))
        assertTrue("Following row must move with cards: $following -> $followingTop", following < followingTop)
        compose.runOnIdle { subtitle = "Example" }
        advance(96)
        assertTrue(height("card") > small && height("card") < tall)
        advance(1_000)
        assertEquals(small, height("card"), 1f)
        assertEquals(if (largeRtl) 1.5f else 1f, fontScale, 0f)
        capture("$largeRtl-after")
        compose.mainClock.autoAdvance = true
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertTrue(checked)
    }

    @Test fun settingAndSwitchCardsResizeWithTheirFollowingRows() = reflow(false)
    @Test fun largeTextRtlDarkCardsResizeAndStayInteractive() = reflow(true)
    @Test fun panelRowsAndSwitchesResizeContinuously() { rowStyle = 1; reflow(false) }
    @Test fun subpageRowsAndStackedSwitchesResizeContinuously() { rowStyle = 2; reflow(true) }

    @Test fun scalingThroughTextWrapMovesCardsAndFollowingContentContinuously() {
        percent = 80
        subtitle = "Example settings description with wrapping text ".repeat(2)
        show(largeRtl = true)
        val small = height("card")
        compose.runOnIdle { percent = 120 }
        val heights = mutableListOf<Float>()
        repeat(12) { frame ->
            advance(32)
            heights += height("card")
            if (frame == 2) capture("scale-and-wrap-intermediate")
        }
        advance(1_000)
        val tall = height("card")
        capture("scale-and-wrap-final")
        File(context.filesDir, "scale-motion-wrap.txt").writeText("$small -> $tall: $heights")
        assertTrue(tall > small)
        assertTrue(heights.any { it > small + 1 && it < tall - 1 })
        assertTrue(heights.zipWithNext().all { (a, b) -> b >= a - 1f })
        assertEquals(base * 1.2f, density, .001f)
        assertEquals(1.5f, fontScale, 0f)
        assertEquals(1, mounts)
    }

    @Test fun reducedMotionAndDisablingMidFlightApplyImmediately() {
        motion = false
        show()
        val start = width()
        compose.runOnIdle { percent = 120 }
        advance(32)
        assertEquals(start * 1.2f, width(), 1f)
        compose.runOnIdle { subtitle = "One\nTwo\nThree" }
        advance(32)
        val settledHeight = height("card")
        advance(96)
        assertEquals(settledHeight, height("card"), 0f)
        compose.runOnIdle { motion = true }
        advance(32)
        compose.runOnIdle { percent = 80 }
        advance(96)
        assertTrue(width() > start * .8f && width() < start * 1.2f)
        compose.runOnIdle { motion = false }
        advance(32)
        assertEquals(start * .8f, width(), 1f)
    }

    @Test fun disabledSystemAnimationsApplyWithoutIntermediateFrames() {
        durationScale = 0f
        show()
        val start = width()
        compose.runOnIdle { percent = 80 }
        advance(32)
        assertEquals(start * .8f, width(), 1f)
        val settled = width()
        advance(96)
        assertEquals(settled, width(), 0f)
    }

    @Test fun removingSensitiveContentDoesNotWaitForSizeAnimation() {
        show()
        compose.runOnIdle { percent = 120 }
        advance(64)
        compose.runOnIdle { visible = false }
        advance(32)
        compose.onNodeWithTag("secret").assertDoesNotExist()
        compose.onNodeWithTag("card").assertDoesNotExist()
        assertEquals(1, mounts)
    }

    @Test fun persistedInitialScaleDoesNotAnimateFromDefaultOrChangeFontScale() {
        percent = 80
        show(largeRtl = true)
        val initial = width()
        assertEquals(base * .8f, density, .001f)
        advance(96)
        assertEquals(initial, width(), 0f)
        assertEquals(1.5f, fontScale, 0f)
    }

    @Test fun sheetCommitsOnlyOnReleaseKeepsSliderBoundsAndResets() {
        show()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("card").performClick()
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        slider.assertIsDisplayed()
        val initial = slider.fetchSemanticsNode().boundsInWindow
        slider.performTouchInput { down(center); moveTo(Offset(width * .85f, centerY)) }
        compose.waitForIdle()
        assertEquals(0, saves)
        assertEquals(100, percent)
        slider.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, saves)
        assertTrue(percent > 100)
        assertEquals(initial, slider.fetchSemanticsNode().boundsInWindow)
        compose.onNodeWithText(context.getString(R.string.interface_scale_reset)).performClick()
        compose.waitForIdle()
        assertEquals(100, percent)
        assertEquals(2, saves)
        compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertFalse(sheet)
        assertEquals(1, mounts)
    }
}

package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.screens.InterfaceScaleSelectionSheet
import takagi.ru.monica.ui.theme.MonicaTheme

class MonicaSliderInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var value by mutableFloatStateOf(50f)
    private var enabled by mutableStateOf(true)
    private var feedback by mutableStateOf(true)
    private var motion by mutableStateOf(true)
    private var finished = 0
    private val requests = mutableListOf<Int>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(rtl: Boolean = false, dark: Boolean = false, large: Boolean = false, recordHaptics: Boolean = true,
                     content: @Composable () -> Unit = {
                         Text("50 / 100")
                         MonicaSlider(value, { value = it }, Modifier.fillMaxWidth().testTag("wheel"),
                             enabled = enabled, valueRange = 0f..100f, steps = 99,
                             onValueChangeFinished = { finished++ })
                     }) {
        val host = object : View(context) {
            override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
                org.junit.Assert.assertEquals(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING, flags)
                requests += feedbackConstant
                return true // Record submission only; service completion has a separate integration test.
            }
        }
        compose.setContent {
            val realView = LocalView.current
            Column {
                if (recordHaptics) AndroidView(factory = { host }, modifier = Modifier.size(1.dp))
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalView provides if (recordHaptics) host else realView,
                    LocalExpansionAnimationsEnabled provides motion,
                    LocalHapticFeedbackEnabled provides feedback,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density, if (large) 1.5f else 1f)) {
                    MonicaTheme(darkTheme = dark) {
                        Column(Modifier.width(320.dp).padding(12.dp)) { content() }
                    }
                }
            }
        }
    }

    @Test fun dragTicksInBothDirectionsAndCommitsOnlyOnRelease() {
        show()
        val slider = compose.onNodeWithTag("wheel")
        slider.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY), 160) }
        compose.runOnIdle { assertEquals(0, finished); assertTrue(value > 70f) }
        val outward = requests.size
        assertTrue(outward > 0)
        slider.performTouchInput { moveTo(Offset(width * .2f, centerY), 160) }
        compose.runOnIdle { assertTrue(value < 30f); assertTrue(requests.size > outward); assertEquals(0, finished) }
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, finished)
            assertTrue(requests.all { it == HapticFeedbackConstants.CONTEXT_CLICK })
        }
        capture("slider-wheel-dragged")
    }

    @Test fun externalUpdatesAndDisabledOrMutedControlsRemainSilent() {
        show()
        compose.runOnIdle { value = 20f }
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        compose.runOnIdle { feedback = false }
        compose.onNodeWithTag("wheel").performTouchInput { swipe(centerLeft, centerRight, 250) }
        compose.runOnIdle { assertTrue(value > 95f); assertTrue(requests.isEmpty()); enabled = false }
        compose.onNodeWithTag("wheel").assertIsNotEnabled().performTouchInput { click(centerLeft) }
        compose.runOnIdle { assertTrue(value > 95f); assertTrue(requests.isEmpty()) }
    }

    @Test fun accessibilityAndRtlRetainRangeDirectionAndTouchTarget() {
        show(rtl = true, dark = true, large = true)
        val slider = compose.onNodeWithTag("wheel")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(75f)) }
        compose.runOnIdle { assertEquals(75f, value); assertEquals(1, finished) }
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..100f, info.range); assertEquals(99, info.steps)
        slider.assertHeightIsAtLeast(48.dp)
        slider.performTouchInput { click(Offset(width * .1f, centerY)) }
        compose.runOnIdle { assertTrue(value > 80f) }
        capture("slider-wheel-rtl-dark-large")
    }

    @Test fun dpiSheetRetainsDraftUntilReleaseAndResetStillWorks() {
        var persisted by mutableIntStateOf(100)
        var commits = 0
        var dismissed = false
        show(recordHaptics = false) {
            InterfaceScaleSelectionSheet(persisted, { persisted = it; commits++ }, { dismissed = true })
        }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        slider.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY), 160) }
        compose.runOnIdle { assertEquals(100, persisted); assertEquals(0, commits) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertTrue(persisted > 100); assertEquals(1, commits) }
        capture("slider-wheel-dpi-sheet")
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.interface_scale_reset)).performClick()
        compose.runOnIdle { assertEquals(100, persisted); assertEquals(2, commits) }
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun wheelFramesFollowDragAndReducedMotionKeepsFixedThumb() {
        show(recordHaptics = false)
        val slider = compose.onNodeWithTag("wheel")
        val values = StringBuilder()
        for ((pass, animate) in listOf(true, false).withIndex()) {
            compose.runOnIdle { motion = animate; value = 50f }
            slider.performTouchInput { down(center) }
            for ((index, fraction) in listOf(.34f, .46f, .58f, .70f, .62f, .50f, .38f).withIndex()) {
                slider.performTouchInput { moveTo(Offset(width * fraction, centerY), 96) }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals(pass, finished); values.append("$animate,$index,$value\n") }
                val bitmap = slider.captureToImage().asAndroidBitmap()
                File(context.filesDir, "slider-wheel-frame-$animate-$index.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            slider.performTouchInput { up() }
            compose.runOnIdle { assertEquals(pass + 1, finished) }
        }
        File(context.filesDir, "slider-wheel-frame-values.txt").writeText(values.toString())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}

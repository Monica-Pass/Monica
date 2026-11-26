package takagi.ru.monica.ui.haptic

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.LocalHapticFeedbackStrength
import takagi.ru.monica.ui.components.HapticStrengthControl
import takagi.ru.monica.ui.components.MonicaSlider
import takagi.ru.monica.ui.theme.MonicaTheme

/** Real controls, real host and real Android service; no intercepted View or motor calls. */
class HapticMotorUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemTouchOnPreviewSwitchAndSliderOutputOnce() = verifyControls(1)

    @Test fun systemTouchOffPreviewSwitchAndSliderOutputOnce() = verifyControls(0)

    private fun verifyControls(systemSetting: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
        require(user > 0 && context.packageName.contains("hapticstrength"))
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        fun ownOutput() = shell("dumpsys vibrator_manager").lineSequence()
            .filter { it.contains(context.packageName + " (uid=") && it.contains("|") }
            .map(String::trim).toSet()
        var strength by mutableIntStateOf(1)
        var enabled by mutableStateOf(true)
        var checked by mutableStateOf(false)
        var value by mutableFloatStateOf(10f)
        val evidence = StringBuilder()
        fun verify(label: String, expected: Int = 1, action: () -> Unit) {
            val before = ownOutput()
            action()
            compose.waitForIdle()
            val deadline = SystemClock.uptimeMillis() + if (expected == 0) 150 else 1_000
            var delta: Set<String>
            do {
                SystemClock.sleep(50)
                delta = ownOutput() - before
            } while (delta.none { it.contains("|             finished |") } && SystemClock.uptimeMillis() < deadline)
            evidence.appendLine(label).appendLine(delta.joinToString("\n"))
            assertEquals(label, expected, delta.count { it.contains("|             finished |") && it.contains("usage: TOUCH") })
            assertTrue("$label must not silently lose an output", delta.none { it.contains("ignored_unsupported") })
        }
        val oldSetting = shell("settings --user $user get system haptic_feedback_enabled")
        try {
            require(shell("am get-current-user") == user.toString())
            shell("settings --user $user put system haptic_feedback_enabled $systemSetting")
            val expectedPolicy = if (systemSetting == 0) "TOUCH = OFF" else "TOUCH = MEDIUM"
            val policyDeadline = SystemClock.uptimeMillis() + 1_000
            while (!shell("dumpsys vibrator_manager").contains(expectedPolicy) && SystemClock.uptimeMillis() < policyDeadline) SystemClock.sleep(50)
            val policy = shell("dumpsys vibrator_manager").substringBefore("  Vibrators:")
            evidence.appendLine(policy)
            assertTrue("Must exercise the actual system policy", policy.contains(expectedPolicy))
            compose.setContent {
                CompositionLocalProvider(LocalHapticFeedbackEnabled provides enabled,
                    LocalHapticFeedbackStrength provides strength) {
                    MonicaTheme {
                        Column(Modifier.width(360.dp)) {
                            MonicaSwitch(checked, { checked = it }, Modifier.testTag("real_switch"))
                            HapticStrengthControl(enabled, strength, {
                                enabled = it > 0
                                if (it > 0) strength = it
                            })
                            MonicaSlider(value, { value = it }, Modifier.testTag("real_slider"),
                                valueRange = 0f..100f, steps = 99)
                        }
                    }
                }
            }
            compose.waitForIdle()
            verify("preview-light") { compose.onNodeWithTag("haptic_test").performTouchInput { click() } }
            verify("switch-light") { compose.onNodeWithTag("real_switch").performTouchInput { click() } }
            verify("slider-light") { compose.onNodeWithTag("real_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) } }
            verify("select-strong") { compose.onNodeWithTag("haptic_strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) } }
            verify("preview-strong") { compose.onNodeWithTag("haptic_test").performClick() }
            verify("slider-strong") { compose.onNodeWithTag("real_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(30f) } }
            verify("disable", 0) { compose.onNodeWithTag("haptic_strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) } }
            compose.onNodeWithTag("haptic_test").assertIsNotEnabled()
            verify("disabled-switch", 0) { compose.onNodeWithTag("real_switch").performClick() }
            verify("disabled-slider", 0) { compose.onNodeWithTag("real_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) } }
            verify("reenable-light") { compose.onNodeWithTag("haptic_strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) } }
            assertEquals(systemSetting.toString(), shell("settings --user $user get system haptic_feedback_enabled"))
        } finally {
            File(context.filesDir, "haptic-motor-ui-system-$systemSetting.txt").writeText(evidence.toString())
            shell(if (oldSetting == "null") "settings --user $user delete system haptic_feedback_enabled"
                else "settings --user $user put system haptic_feedback_enabled $oldSetting")
        }
    }
}

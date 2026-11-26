package takagi.ru.monica.ui.haptic

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Opt-in service-output test: View's Boolean alone is not a motor-output receipt. */
class HapticMotorOutputInstrumentedTest {
    @Test fun realHostFeedbackReachesTouchVibrationServiceOnce() {
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
        val oldSetting = shell("settings --user $user get system haptic_feedback_enabled")
        val evidence = StringBuilder()
        val missing = mutableListOf<String>()
        try {
            shell("settings --user $user put system haptic_feedback_enabled 1")
            ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { it.setContentView(View(it)) }
                instrumentation.waitForIdleSync()
                for (phase in listOf("light-switch", "light-slider", "strong-switch", "strong-slider", "legacy-light")) {
                    val before = ownOutput()
                    scenario.onActivity { activity ->
                        val host = activity.findViewById<View>(android.R.id.content)
                        assertTrue(host.isAttachedToWindow && host.isShown)
                        host.isHapticFeedbackEnabled = false
                        val helper = HapticFeedbackHelper(activity, host, strength = if (phase.startsWith("strong")) 2 else 1)
                        val result = when {
                            phase == "legacy-light" -> helper.performLightClick()
                            phase.endsWith("slider") -> helper.performSliderTick()
                            else -> helper.performSwitchToggle()
                        }
                        evidence.appendLine("$phase result=$result")
                        assertFalse("Host preference must be preserved", host.isHapticFeedbackEnabled)
                    }
                    // The Android service submits and finishes asynchronously; bounded observation only.
                    val deadline = SystemClock.uptimeMillis() + 1_000
                    var delta: Set<String>
                    do {
                        SystemClock.sleep(50)
                        delta = ownOutput() - before
                    } while (delta.none { it.contains("|             finished |") } && SystemClock.uptimeMillis() < deadline)
                    evidence.appendLine(delta.joinToString("\n"))
                    val completed = delta.count { it.contains("|             finished |") && it.contains("usage: TOUCH") }
                    if (completed != 1) missing += "$phase: expected one TOUCH output, got $completed"
                }
            }
        } finally {
            File(context.filesDir, "haptic-motor-output.txt").writeText(evidence.toString())
            shell(if (oldSetting == "null") "settings --user $user delete system haptic_feedback_enabled"
                else "settings --user $user put system haptic_feedback_enabled $oldSetting")
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }
}

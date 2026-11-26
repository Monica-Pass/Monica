package takagi.ru.monica.ui.haptic

import android.graphics.Bitmap
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.LocalHapticFeedbackStrength
import takagi.ru.monica.ui.components.HapticStrengthControl
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.PageAdjustmentSettingsSnapshot

class HapticStrengthInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var strength by mutableIntStateOf(1)
    private var feedback by mutableStateOf(true)
    private var checked by mutableStateOf(false)
    private var commits = 0
    private val requests = mutableListOf<Int>()
    private lateinit var host: View

    private fun show(large: Boolean = false, recording: Boolean = true) {
        host = object : View(context) {
            override fun performHapticFeedback(constant: Int, flags: Int): Boolean {
                assertEquals(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING, flags)
                // Reproduce a host that refuses feedback unless explicitly enabled.
                if (!isHapticFeedbackEnabled) return false
                requests += constant; return true
            }
        }.apply { isHapticFeedbackEnabled = false }
        compose.setContent {
            val realView = LocalView.current
            Column {
                if (recording) AndroidView({ host }, Modifier.size(1.dp))
                CompositionLocalProvider(LocalView provides if (recording) host else realView,
                    LocalHapticFeedbackEnabled provides feedback,
                    LocalHapticFeedbackStrength provides strength,
                    LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                    MonicaTheme(darkTheme = large) {
                        Surface {
                            Column(Modifier.width(if (large) 280.dp else 360.dp)
                                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                                MonicaSwitch(checked, { checked = it }, Modifier.testTag("switch"))
                                HapticStrengthControl(feedback, strength, {
                                    commits++; feedback = it > 0
                                    if (it > 0) strength = it
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun hostDisabledFeedbackIsEnabledForRequestAndRestored() {
        show()
        compose.onNodeWithTag("switch").performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(checked); assertFalse(host.isHapticFeedbackEnabled)
            assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK), requests)
            strength = 2
        }
        compose.onNodeWithTag("switch").performClick()
        compose.runOnIdle {
            assertFalse(checked); assertFalse(host.isHapticFeedbackEnabled)
            assertEquals(HapticFeedbackConstants.LONG_PRESS, requests.last())
            assertEquals(2, requests.size)
        }
    }

    @Test fun zeroDisablesAndSliderCanReenableWithSingleSelectedPreview() {
        show()
        val slider = compose.onNodeWithTag("haptic_strength_slider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("haptic_test").assertDoesNotExist()
        compose.runOnIdle { assertFalse(feedback); assertTrue(requests.isEmpty()); assertEquals(1, commits) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle {
            assertTrue(feedback); assertEquals(2, strength); assertEquals(2, commits)
            assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), requests)
        }
        compose.runOnIdle { strength = 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests.size) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle {
            assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, requests.last())
            assertEquals(3, requests.size)
        }
    }

    @Test fun narrowDarkLargeTextKeepsSliderReachableWithoutExtraControls() {
        show(large = true, recording = false)
        compose.onNodeWithTag("haptic_strength_slider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("haptic_test").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.haptic_strength_description)).assertDoesNotExist()
        compose.onNodeWithTag("haptic_strength_slider")
            .assertContentDescriptionEquals(context.getString(R.string.haptic_strength_description))
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "haptic-strength-dark-large.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun preferencesAndSettingsSnapshotKeepStrengthAndOldDefaults() = runBlocking {
        val manager = SettingsManager(context)
        val original = manager.exportPageAdjustmentSettings()
        try {
            assertEquals(1, PageAdjustmentSettingsSnapshot().hapticFeedbackStrength)
            manager.updateHapticFeedbackStrength(2)
            var read = manager.exportPageAdjustmentSettings()
            assertTrue(read.hapticFeedbackEnabled); assertEquals(2, read.hapticFeedbackStrength)
            manager.updateHapticFeedbackStrength(0)
            read = manager.exportPageAdjustmentSettings()
            assertFalse(read.hapticFeedbackEnabled); assertEquals(2, read.hapticFeedbackStrength)
            manager.updateHapticFeedbackEnabled(true)
            val exported = manager.exportPageAdjustmentSettings()
            assertTrue(exported.hapticFeedbackEnabled); assertEquals(2, exported.hapticFeedbackStrength)
            manager.importPageAdjustmentSettings(exported.copy(hapticFeedbackStrength = -1))
            assertEquals(1, manager.exportPageAdjustmentSettings().hapticFeedbackStrength)
            manager.importPageAdjustmentSettings(exported)
            assertEquals(2, manager.exportPageAdjustmentSettings().hapticFeedbackStrength)
            // The shared UI flow replays the last emission; await this committed edit.
            val observed = withTimeout(5_000) {
                manager.settingsFlow.first { it.hapticFeedbackEnabled && it.hapticFeedbackStrength == 2 }
            }
            assertEquals(2, observed.hapticFeedbackStrength)
        } finally { manager.importPageAdjustmentSettings(original) }
    }

    @Test fun extensionPageSavesStrengthAndReturnsNormally() {
        var back = 0
        var dark by mutableStateOf(false)
        val manager = SettingsManager(context)
        val original = runBlocking { manager.exportPageAdjustmentSettings() }
        try {
            runBlocking { manager.updateHapticFeedbackStrength(1) }
            compose.setContent {
                val settings by manager.settingsFlow.collectAsState(takagi.ru.monica.data.AppSettings())
                val scope = rememberCoroutineScope()
                MonicaTheme(darkTheme = dark) {
                    takagi.ru.monica.ui.screens.ExtensionsScreen(
                        onNavigateBack = { back++ }, hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
                        onHapticFeedbackChange = { scope.launch { manager.updateHapticFeedbackEnabled(it) } },
                        hapticFeedbackStrength = settings.hapticFeedbackStrength,
                        onHapticStrengthChange = { scope.launch { manager.updateHapticFeedbackStrength(it) } },
                    )
                }
            }
            compose.onNodeWithTag("haptic_strength_slider").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            runBlocking { withTimeout(5_000) { manager.settingsFlow.first { it.hapticFeedbackStrength == 2 } } }
            assertEquals(2, runBlocking { manager.exportPageAdjustmentSettings() }.hapticFeedbackStrength)
            for (darkTheme in listOf(false, true)) {
                compose.runOnIdle { dark = darkTheme }
                compose.waitForIdle()
                compose.onNodeWithTag("haptic_strength_slider").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("haptic_test").assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.haptic_strength_description)).assertDoesNotExist()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "haptic-strength-extension-${if (darkTheme) "dark" else "light"}.png")
                    .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.go_back)).performClick()
            compose.runOnIdle { assertEquals(1, back) }
        } finally { runBlocking { manager.importPageAdjustmentSettings(original) } }
    }

    @Test fun systemTouchOffStillSubmitsAppControlledFeedback() {
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
        require(user > 0 && context.packageName.contains("hapticstrength"))
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
        val before = shell("settings --user $user get system haptic_feedback_enabled")
        try {
            shell("settings --user $user put system haptic_feedback_enabled 0")
            show()
            compose.onNodeWithTag("haptic_strength_slider")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            compose.runOnIdle { assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), requests) }
            assertEquals("0", shell("settings --user $user get system haptic_feedback_enabled"))
        } finally {
            shell(if (before == "null") "settings --user $user delete system haptic_feedback_enabled"
                else "settings --user $user put system haptic_feedback_enabled $before")
        }
    }
}

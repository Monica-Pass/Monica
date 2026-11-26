package takagi.ru.monica.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.GeneratorScreen
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.ui.screens.SettingsScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.GeneratorType
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel

/** Real settings/generator pages and Android vibrator service, in an explicitly isolated user. */
class GeneratorFeedbackInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var feedback by mutableStateOf(true)
    private var strength by mutableIntStateOf(1)
    private val evidence = StringBuilder()

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    private fun ownOutput() = shell("dumpsys vibrator_manager").lineSequence()
        .filter { it.contains(context.packageName + " (uid=") && it.contains("|") }
        .map(String::trim).toSet()

    private fun verifyOutput(label: String, expected: Int, action: () -> Unit) {
        val before = ownOutput()
        action()
        compose.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + if (expected == 0) 160 else 1_000
        var delta: Set<String>
        do {
            SystemClock.sleep(50)
            delta = ownOutput() - before
        } while (delta.none { it.contains("|             finished |") } && SystemClock.uptimeMillis() < deadline)
        evidence.appendLine(label).appendLine(delta.joinToString("\n"))
        assertEquals(label, expected, delta.count { it.contains("|             finished |") && it.contains("usage: TOUCH") })
        assertTrue("$label must reach a supported motor effect", delta.none { it.contains("ignored_unsupported") })
    }

    private fun withSystemTouchOff(name: String, body: () -> Unit) {
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
        require(user > 0 && context.packageName.contains("generatorhapticstrength"))
        require(shell("am get-current-user") == user.toString())
        val old = shell("settings --user $user get system haptic_feedback_enabled")
        try {
            shell("settings --user $user put system haptic_feedback_enabled 0")
            val deadline = SystemClock.uptimeMillis() + 1_000
            while (!shell("dumpsys vibrator_manager").contains("TOUCH = OFF") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(shell("dumpsys vibrator_manager").contains("TOUCH = OFF"))
            evidence.appendLine("System TOUCH = OFF; unmodified host View; real service output")
            body()
            assertEquals("0", shell("settings --user $user get system haptic_feedback_enabled"))
        } finally {
            File(context.filesDir, "generator-feedback-$name.txt").writeText(evidence.toString())
            shell(if (old == "null") "settings --user $user delete system haptic_feedback_enabled"
                else "settings --user $user put system haptic_feedback_enabled $old")
        }
    }

    @Test fun screenshotProtectionCardTogglesPersistsAndVibratesOnce() = withSystemTouchOff("screenshot") {
        val manager = SettingsManager(context)
        val initial = runBlocking { manager.settingsFlow.first() }.screenshotProtectionEnabled
        val model = SettingsViewModel(manager)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalHapticFeedbackEnabled provides feedback,
                    LocalHapticFeedbackStrength provides strength) {
                    MonicaTheme {
                        SettingsScreen(model, {}, {}, {}, {})
                    }
                }
            }
            compose.waitUntil(5_000) { model.settingsLoaded.value }
            val row = compose.onNodeWithText(context.getString(Setting.SCREENSHOT_PROTECTION.titleRes))
            row.performScrollTo().assertIsDisplayed()
            for ((index, next) in listOf(!initial, initial).withIndex()) {
                compose.runOnIdle { strength = index + 1 }
                verifyOutput("screenshot-card-${index + 1}", 1) {
                    row.performTouchInput { click() }
                    compose.waitUntil(5_000) { model.settings.value.screenshotProtectionEnabled == next }
                    assertEquals(next, runBlocking { SettingsManager(context).settingsFlow.first() }.screenshotProtectionEnabled)
                }
            }
            compose.runOnIdle { feedback = false }
            verifyOutput("screenshot-card-muted", 0) {
                row.performTouchInput { click() }
                compose.waitUntil(5_000) { model.settings.value.screenshotProtectionEnabled != initial }
            }
            compose.runOnIdle { feedback = true }
            verifyOutput("external-setting-change", 0) {
                runBlocking { manager.updateScreenshotProtectionEnabled(initial) }
                compose.waitUntil(5_000) { model.settings.value.screenshotProtectionEnabled == initial }
            }
        } finally {
            runBlocking { manager.updateScreenshotProtectionEnabled(initial) }
            model.viewModelScope.cancel()
        }
    }

    @Test fun generatorLengthDragTicksInBothDirectionsForAllTextModes() = withSystemTouchOff("drag") {
        runBlocking { assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready) }
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
            SecurityManager(context), customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
        val generator = GeneratorViewModel()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalHapticFeedbackEnabled provides feedback,
                    LocalHapticFeedbackStrength provides strength) {
                    MonicaTheme { GeneratorScreen({}, passwords, viewModel = generator) }
                }
            }
            compose.waitUntil(10_000) { generator.symbolResult.value.isNotEmpty() }
            for (type in listOf(GeneratorType.SYMBOL, GeneratorType.PASSWORD, GeneratorType.PASSPHRASE, GeneratorType.PIN)) {
                compose.runOnIdle { generator.updateSelectedGenerator(type) }
                compose.waitUntil(10_000) {
                    when (type) {
                        GeneratorType.SYMBOL -> generator.symbolResult.value
                        GeneratorType.PASSWORD -> generator.passwordResult.value
                        GeneratorType.PASSPHRASE -> generator.passphraseResult.value
                        GeneratorType.PIN -> generator.pinResult.value
                        else -> error("Only text modes are exercised here")
                    }.isNotEmpty()
                }
                compose.waitForIdle()
                val slider = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).onFirst()
                slider.performScrollTo().assertIsDisplayed()
                slider.performTouchInput { down(Offset(width * .45f, centerY)) }
                compose.waitForIdle()
                SystemClock.sleep(100)
                val before = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                evidence.appendLine("$type before=$before bounds=${slider.fetchSemanticsNode().boundsInRoot}")
                verifyOutput("$type-drag-right", 1) {
                    slider.performTouchInput { moveTo(Offset(width * .85f, centerY), 160) }
                    compose.waitForIdle()
                    evidence.appendLine("$type right=${slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current} bounds=${slider.fetchSemanticsNode().boundsInRoot}")
                }
                val right = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                assertTrue("Actual $type setting must increase", right > before)
                verifyOutput("$type-drag-left", 1) {
                    slider.performTouchInput { moveTo(Offset(width * .15f, centerY), 160) }
                }
                val left = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                assertTrue("Actual $type setting must decrease", left < right)
                verifyOutput("$type-release", 0) { slider.performTouchInput { up() } }
                compose.runOnIdle { feedback = false }
                verifyOutput("$type-muted", 0) {
                    slider.performTouchInput { swipe(centerLeft, centerRight, 250) }
                }
                compose.runOnIdle { feedback = true }
            }
        } finally {
            passwords.viewModelScope.cancel()
            generator.viewModelScope.cancel()
            db.close()
        }
    }
}

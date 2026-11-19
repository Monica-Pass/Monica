package takagi.ru.monica.ui.common.layout

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.SettingsManager
import java.io.File

/** Only the isolated application IDs created for this task may run the native resize checks. */
class AdaptiveMainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "SyntheticAdaptive329"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(25_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
    private fun screen(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "adaptive-native-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(context.filesDir, "adaptive-native-$name.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }
    private fun size(w: Int, h: Int) {
        shell("wm size ${w}x$h")
        compose.waitForIdle()
    }

    @Test fun actualLoginEditResizeSaveSettingsRotationAndLock() {
        check(context.packageName in setOf("takagi.ru.monica.adaptive20261119", "takagi.ru.monica.adaptivefd20261119"))
        val originalSize = shell("wm size")
        val originalDensity = shell("wm density")
        val originalRotation = shell("settings get system user_rotation")
        val originalAuto = shell("settings get system accelerometer_rotation")
        val originalFont = shell("settings get system font_scale")
        fun overrideValue(text: String) = text.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            shell("wm density 160")
            size(1200, 900)
            runBlocking {
                assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
                SettingsManager(context).apply {
                    updateQuickSetupCompleted(true)
                    updateLanguage(Language.CHINESE)
                    updateThemeMode(ThemeMode.DARK)
                    updateBiometricEnabled(false)
                    updateScreenshotProtectionEnabled(false)
                    updateAutoLockMinutes(5)
                    updatePasswordContentEditorEnabled(true)
                    updateAutoHideBottomNavWhenSingleTab(false)
                    updateVaultOverviewEnabled(false)
                    BottomNavContentTab.entries.forEach { updateBottomNavVisibility(it, it == BottomNavContentTab.PASSWORDS) }
                }
            }
            val security = SecurityManager(context)
            if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword(password))
            else assertTrue(security.verifyMasterPassword(password))
            val db = PasswordDatabase.getDatabase(context)
            runBlocking {
                db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title.startsWith("Adaptive fixture") }.forEach { db.passwordEntryDao().deletePasswordEntry(it) }
                db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Adaptive fixture 邮箱", username = "demo@example.invalid",
                    website = "https://example.invalid", password = security.encryptData("SyntheticRecord329")))
            }
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitFor(hasSetTextAction())
            screen("login-4x3")
            val input = compose.onNode(hasSetTextAction())
            input.performTextInput(password)
            val originalActivity = arrayOfNulls<MainActivity>(1)
            scenario.onActivity { originalActivity[0] = it }
            size(400, 640)
            input.assertTextContains(password)
            scenario.onActivity { assertSame("Resize must not recreate the secure editor host", originalActivity[0], it) }
            screen("login-outer")
            input.performImeAction()
            waitFor(hasText("Adaptive fixture 邮箱"))
            size(1200, 900)
            compose.onAllNodesWithText("Adaptive fixture 邮箱").onFirst().performClick()
            waitFor(hasContentDescription("编辑"))
            screen("password-detail-4x3")
            size(400, 640)
            compose.onNodeWithTag("adaptive-list").assertIsNotDisplayed()
            compose.onAllNodesWithContentDescription(context.getString(R.string.add)).assertCountEquals(0)
            screen("password-detail-outer")
            size(1200, 900)
            compose.onAllNodesWithContentDescription("编辑").onFirst().performClick()
            waitFor(hasTestTag("password_editor_title"))
            compose.onNodeWithTag("password_editor_title").performTextReplacement("Adaptive fixture 已保存")
            Espresso.closeSoftKeyboard()
            screen("editor-wide")
            size(400, 640)
            compose.onNodeWithTag("password_editor_title").assertTextContains("Adaptive fixture 已保存")
            screen("editor-outer")
            size(900, 900)
            compose.onNodeWithTag("password_editor_title").assertTextContains("Adaptive fixture 已保存")
            screen("editor-square")
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(25_000) { runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.any { it.title == "Adaptive fixture 已保存" } }
            val saved = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.single { it.title == "Adaptive fixture 已保存" }
            assertEquals("SyntheticRecord329", security.decryptData(saved.password))
            size(720, 960)
            screen("medium-window")
            compose.onAllNodesWithContentDescription("设置", useUnmergedTree = true).onLast().performClick()
            waitFor(hasTestTag("settings_search_input"))
            screen("settings-medium")
            size(1200, 900)
            screen("settings-4x3")
            compose.onNodeWithTag("settings_search_input").performTextInput("HyperOS")
            Espresso.closeSoftKeyboard()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("settings_search_input").assertTextContains("HyperOS")
            screen("settings-rotated")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            size(900, 900)
            compose.onNodeWithTag("settings_search_input").assertTextContains("HyperOS")
            screen("settings-search-square")
            compose.runOnIdle { SessionManager.markLocked(); SecurityManager.clearRuntimeUnlockCache() }
            waitFor(hasSetTextAction())
            compose.onNodeWithTag("password_editor_title").assertDoesNotExist()
            compose.onNodeWithTag("settings_search_input").assertDoesNotExist()
            screen("locked-square")
            // Font changes may recreate the Activity: authentication must remain locked.
            runBlocking { SettingsManager(context).updateThemeMode(ThemeMode.LIGHT) }
            shell("settings put system font_scale 1.5")
            size(960, 400)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            waitFor(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(password)
            scenario.onActivity { activity ->
                val keyboard = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            compose.waitForIdle()
            compose.onNodeWithText("解锁").performScrollTo().assertIsDisplayed()
            screen("login-short-large-text-light")
            compose.onNodeWithText("解锁").performClick()
            waitFor(hasText("Adaptive fixture 已保存"))
            screen("list-short-large-text-light")
            compose.onAllNodesWithContentDescription("设置", useUnmergedTree = true).onLast().performClick()
            waitFor(hasTestTag("settings_search_input"))
            screen("settings-short-large-text-light")
        } finally {
            scenario?.close()
            shell("wm size ${overrideValue(originalSize)}")
            shell("wm density ${overrideValue(originalDensity)}")
            shell("settings put system user_rotation $originalRotation")
            shell("settings put system accelerometer_rotation $originalAuto")
            shell("settings put system font_scale $originalFont")
        }
    }
}

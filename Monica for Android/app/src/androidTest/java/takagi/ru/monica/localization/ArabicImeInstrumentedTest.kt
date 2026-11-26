package takagi.ru.monica.localization

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.SettingsManager

@RunWith(AndroidJUnit4::class)
class ArabicImeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installedKeyboardUsesTheSavedLanguageAndRefreshesWhileOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val manager = SettingsManager(context)
        val originalLanguage = runBlocking { manager.settingsFlow.first().language }
        val originalLocale = Locale.getDefault()
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
        require(user > 0 && android.os.Process.myUid() / 100000 == user)
        val originalIme = shell("settings --user $user get secure default_input_method").trim()
        val component = ComponentName(context, takagi.ru.monica.ime.MonicaInputMethodService::class.java).flattenToShortString()
        val wasEnabled = shell("ime list --user $user -s").lineSequence().any {
            ComponentName.unflattenFromString(it.trim()) == ComponentName.unflattenFromString(component)
        }
        val originalFlags = automation.serviceInfo.flags
        assertTrue("The emulator must have a restorable input method", originalIme.matches(Regex("[A-Za-z0-9_.$/]+")) && originalIme != "null")
        lateinit var editor: EditText

        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking { manager.updateLanguage(Language.ARABIC) }
            // A service launched outside the main activity must read the saved choice.
            Locale.setDefault(Locale.ENGLISH)
            shell("ime enable --user $user $component")
            shell("ime set --user $user $component")
            compose.setContent {
                AndroidView(factory = { viewContext ->
                    EditText(viewContext).apply { editor = this; hint = "Locale test" }
                })
            }
            compose.runOnIdle {
                editor.requestFocus()
                editor.post {
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            waitForKeyboardLabel("الملء التلقائي")
            assertKeyboardDirection("الملء التلقائي", rtl = true)
            val screenshot = automation.takeScreenshot()
            File(context.filesDir, "arabic-keyboard.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()

            runBlocking { manager.updateLanguage(Language.ENGLISH) }
            waitForKeyboardLabel("Autofill")
            assertKeyboardDirection("Autofill", rtl = false)
            saveScreenshot("arabic-keyboard-english.png")
            runBlocking { manager.updateLanguage(Language.ARABIC) }
            waitForKeyboardLabel("الملء التلقائي")
            assertKeyboardDirection("الملء التلقائي", rtl = true)
            saveScreenshot("arabic-keyboard-restored.png")
            assertTrue("Latin key must remain tappable after language changes", tapKeyboardKey("q"))
            compose.waitUntil(5000) { editor.text.toString() == "q" }
        } finally {
            shell("ime set --user $user $originalIme")
            if (!wasEnabled) shell("ime disable --user $user $component")
            runBlocking { manager.updateLanguage(originalLanguage) }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            Locale.setDefault(originalLocale)
        }
    }

    private fun assertKeyboardDirection(autofill: String, rtl: Boolean) {
        fun bounds(label: String): android.graphics.Rect {
            fun visit(node: AccessibilityNodeInfo): android.graphics.Rect? {
                try {
                    node.refresh()
                    if (node.text?.toString() == label || node.contentDescription?.toString() == label)
                        return android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child)?.let { return it } }
                    return null
                } finally { node.recycle() }
            }
            val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            try {
                return windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .firstNotNullOfOrNull { it.root?.let(::visit) } ?: error("Missing key: $label")
            } finally { windows.forEach { it.recycle() } }
        }
        val keyboardLabel = if (rtl) "لوحة المفاتيح" else "Keyboard"
        try {
            compose.waitUntil(5000) {
                runCatching { (bounds(keyboardLabel).centerX() > bounds(autofill).centerX()) == rtl }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            File(instrumentation.targetContext.filesDir, "arabic-keyboard-direction-failure.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw AssertionError("Expected RTL=$rtl; labels=${keyboardLabels()}", error)
        }
        assertTrue("Latin QWERTY must not reverse in Arabic", bounds("q").centerX() < bounds("p").centerX())
    }

    private fun waitForKeyboardLabel(text: String) {
        try {
            compose.waitUntil(20_000) { text in keyboardLabels() }
        } catch (error: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.filesDir, "arabic-keyboard-failure.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            throw AssertionError("Expected IME label '$text'; actual labels: ${keyboardLabels()}", error)
        }
    }

    private fun keyboardLabels(): List<String> {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        return try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .flatMap { window -> window.root?.let(::textLabels).orEmpty() }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun textLabels(node: AccessibilityNodeInfo): List<String> {
        return try {
            // UiAutomation can cache nodes across updates in the separate IME process.
            node.refresh()
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::textLabels).orEmpty() }
        } finally {
            node.recycle()
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun tapKeyboardKey(label: String): Boolean {
        fun visit(node: AccessibilityNodeInfo): android.graphics.Rect? {
            try {
                node.refresh()
                if (node.text?.toString() == label || node.contentDescription?.toString() == label)
                    return android.graphics.Rect().also { node.getBoundsInScreen(it) }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            } finally { node.recycle() }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val windows = automation.windows
        val bounds = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .firstNotNullOfOrNull { it.root?.let(::visit) }
        } finally { windows.forEach { it.recycle() } } ?: return false
        if (bounds.isEmpty) return false
        val start = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, time: Long): Boolean {
            val input = android.view.MotionEvent.obtain(start, time, action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            return try { automation.injectInputEvent(input, true) } finally { input.recycle() }
        }
        val down = event(android.view.MotionEvent.ACTION_DOWN, start)
        val up = event(android.view.MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis())
        return down && up
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}

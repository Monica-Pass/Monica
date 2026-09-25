package takagi.ru.monica.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.fixture.AutofillFormFixtureActivity

/** Uses Android's real IME window, InputConnection and a separate fixture application process. */
class MonicaImeSystemTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
    }

    private fun find(description: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // Compose can reuse a semantics node for a different digit after shuffling.
            // Refresh the OS node before reading its description and bounds.
            if (!node.refresh()) return null
            if (node.contentDescription?.toString() == description) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        for (window in automation.windows) window.root?.let { visit(it)?.let { found -> return found } }
        return null
    }
    private fun waitFor(description: String, predicate: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            find(description)?.takeIf(predicate)?.let { return it }
            android.os.SystemClock.sleep(30)
        }
        error("No matching IME fixture control: $description")
    }
    private fun tap(description: String) {
        val bounds = Rect().also { waitFor(description).getBoundsInScreen(it) }
        val time = android.os.SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Touch on $description must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        android.os.SystemClock.sleep(30)
        send(MotionEvent.ACTION_UP)
    }
    private fun digitOrder(): List<String> = (0..9).map { digit ->
        val bounds = Rect().also { waitFor(digit.toString()).getBoundsInScreen(it) }
        digit.toString() to bounds
    }.sortedWith(compareBy<Pair<String, Rect>> { it.second.top }.thenBy { it.second.left }).map { it.first }

    @Test fun systemKeyboardTypesNumbersAndLettersAndAppliesTheSavedShuffleSwitch(): Unit = runBlocking {
        val serviceComponent = ComponentName(instrumentation.targetContext, MonicaInputMethodService::class.java)
        val component = serviceComponent.flattenToString()
        val fixture = ComponentName(instrumentation.context, AutofillFormFixtureActivity::class.java).flattenToString()
        val previous = shell("settings get secure default_input_method")
        require(previous.matches(Regex("[A-Za-z0-9_./]+")))
        val enabled = shell("settings get secure enabled_input_methods")
        val wasEnabled = enabled.split(':').any {
            ComponentName.unflattenFromString(it.substringBefore(';')) == serviceComponent
        }
        val preferences = AutofillPreferences(instrumentation.targetContext)
        val options = preferences.imeKeyboardOptions.first()
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            preferences.setImeScramblePin(true)
            preferences.setImeHidePinPreview(true)
            shell("ime enable $component")
            shell("ime set $component")
            shell("am start -W -n $fixture --es scenario otp --ez accessibilityOnly true")
            tap("fixture-field-1")
            waitFor("0")
            android.os.SystemClock.sleep(250)
            val layout = digitOrder()
            listOf("0", "9", "2", "6").forEach(::tap)
            waitFor("fixture-field-1") { it.text?.toString() == "0926" }
            assertEquals(layout, digitOrder())
            preferences.setImeScramblePin(false)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (digitOrder() != StandardImePinDigits && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(30)
            }
            val directory = instrumentation.targetContext.getExternalFilesDir("ime-improvements")
            File(directory, "system-number-keyboard.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals(StandardImePinDigits, digitOrder())
            tap("fixture-field-0")
            tap("q")
            waitFor("fixture-field-0") { it.text?.toString() == "q" }
            File(directory, "system-letter-keyboard.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            preferences.setImeScramblePin(options.scramblePin)
            preferences.setImeHidePinPreview(options.hidePinPreview)
            shell("input keyevent KEYCODE_BACK")
            shell("input keyevent KEYCODE_BACK")
            shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $component")
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }
}

package takagi.ru.monica.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
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
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val testUser = android.os.Process.myUid() / 100000
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
        File(instrumentation.targetContext.getExternalFilesDir(null), "project-ime-failure.png").outputStream().use {
            automation.takeScreenshot()?.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        error("No matching IME fixture control: $description")
    }
    private fun openFixture(scenario: String) {
        val instance = java.util.UUID.randomUUID().toString()
        // Use the instrumentation user's context and a fresh activity. A previous
        // autofill test can still have a verification activity or form in this task.
        instrumentation.targetContext.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context, AutofillFormFixtureActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("scenario", scenario)
            putExtra("accessibilityOnly", true)
            putExtra("fixtureInstance", instance)
        })
        waitFor("fixture-instance-$instance")
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
        val component = serviceComponent.flattenToShortString()
        val previous = shell("settings --user $testUser get secure default_input_method")
        require(previous.matches(Regex("[A-Za-z0-9_./]+")))
        val enabled = shell("settings --user $testUser get secure enabled_input_methods")
        val wasEnabled = enabled.split(':').any {
            ComponentName.unflattenFromString(it.substringBefore(';')) == serviceComponent
        }
        val preferences = AutofillPreferences(instrumentation.targetContext)
        val options = preferences.imeKeyboardOptions.first()
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            preferences.setImeScramblePin(true)
            preferences.setImeHidePinPreview(true)
            shell("ime enable --user $testUser $component")
            shell("ime set --user $testUser $component")
            assertEquals(component, shell("settings --user $testUser get secure default_input_method"))
            openFixture("otp")
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
            shell("ime set --user $testUser $previous")
            if (!wasEnabled) shell("ime disable --user $testUser $component")
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }
    @Test fun activeKeyboardSurvivesRepeatedFontScaleChangesAndStillTypes() {
        val serviceComponent = ComponentName(instrumentation.targetContext, MonicaInputMethodService::class.java)
        val component = serviceComponent.flattenToShortString()
        val process = instrumentation.targetContext.packageName + ":ime"
        val previous = shell("settings --user $testUser get secure default_input_method")
        val previousScale = shell("settings --user $testUser get system font_scale")
        require(previous.matches(Regex("[A-Za-z0-9_./]+")))
        require(previousScale == "null" || previousScale.toFloatOrNull() != null)
        val wasEnabled = shell("settings --user $testUser get secure enabled_input_methods").split(':').any {
            ComponentName.unflattenFromString(it.substringBefore(';')) == serviceComponent
        }
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            shell("ime enable --user $testUser $component")
            shell("ime set --user $testUser $component")
            openFixture("login")
            tap("fixture-field-0")
            waitFor("q")
            val originalPid = shell("pidof $process")
            assertTrue("The real IME process must be running", originalPid.isNotBlank())
            listOf("1.5", "1.0", "1.5", "1.0").forEach { scale ->
                // Keep the IME alive and visible while Android rebuilds its window.
                shell("settings --user $testUser put system font_scale $scale")
                android.os.SystemClock.sleep(750)
                assertEquals("Configuration change must not restart the IME", originalPid, shell("pidof $process"))
                openFixture("login")
                tap("fixture-field-0")
                waitFor("q")
                // A newly attached view exposes semantics before its resize animation ends.
                automation.waitForIdle(200, 5000)
                tap("q")
                waitFor("fixture-field-0") { it.text?.toString() == "q" }
                assertEquals("Reattached keyboard must use the original process", originalPid, shell("pidof $process"))
            }
        } finally {
            if (previousScale == "null") shell("settings --user $testUser delete system font_scale")
            else shell("settings --user $testUser put system font_scale $previousScale")
            shell("input keyevent KEYCODE_BACK")
            shell("input keyevent KEYCODE_BACK")
            shell("ime set --user $testUser $previous")
            if (!wasEnabled) shell("ime disable --user $testUser $component")
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }

    @Test fun selectedProjectAccountFillsItsOwnLatestPasswordInSystemForm(): Unit = runBlocking {
        val data = takagi.ru.monica.credentialexchange.TransferFixture()
        val component = ComponentName(instrumentation.targetContext, MonicaInputMethodService::class.java).flattenToShortString()
        val previous = shell("settings --user $testUser get secure default_input_method")
        require(previous.matches(Regex("[A-Za-z0-9_./]+")))
        val wasEnabled = shell("settings --user $testUser get secure enabled_input_methods").split(':').any { it.substringBefore(';') == component }
        val oldFlags = automation.serviceInfo.flags
        fun textNode(text: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (!node.refresh()) return null
                if (node.text?.toString()?.contains(text) == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return automation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
        }
        fun clickText(text: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 15000
            var node: AccessibilityNodeInfo? = null
            while (node == null && android.os.SystemClock.uptimeMillis() < deadline) {
                node = textNode(text)
                if (node == null) {
                    // Expanded actions may be below the IME's bounded viewport when
                    // pre-existing fixture rows precede this account. Scroll the real
                    // vertical list, never select a different account to make it pass.
                    fun scrollList(root: AccessibilityNodeInfo): Boolean {
                        if (!root.refresh()) return false
                        val bounds = Rect().also(root::getBoundsInScreen)
                        if (root.isScrollable && bounds.height() > 80 * instrumentation.targetContext.resources.displayMetrics.density &&
                            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                        for (i in 0 until root.childCount) {
                            if (root.getChild(i)?.let(::scrollList) == true) return true
                        }
                        return false
                    }
                    automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .any { it.root?.let(::scrollList) == true }
                    android.os.SystemClock.sleep(150)
                }
            }
            var current = requireNotNull(node) { "Missing project IME action: $text" }
            while (!current.isClickable && current.parent != null) current = current.parent
            assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
            val project = java.util.UUID.randomUUID().toString()
            val groups = listOf(
                takagi.ru.monica.data.model.ProjectCredentialGroup.Group(username = "ime-primary", passwords = listOf(takagi.ru.monica.data.model.ProjectCredentialGroup.Password(value = "primary-secret"))),
                takagi.ru.monica.data.model.ProjectCredentialGroup.Group(username = "ime-work", passwords = listOf(takagi.ru.monica.data.model.ProjectCredentialGroup.Password(value = "work-secret"))))
            val entries = takagi.ru.monica.data.model.ProjectCredentialGroup.rows(groups).map { row ->
                val entry = takagi.ru.monica.data.PasswordEntry(title = data.prefix, website = "https://example.invalid", username = row.username,
                    password = data.security.encryptData(row.password.value), passwordGroupId = project,
                    appPackageName = instrumentation.context.packageName, isFavorite = true)
                val id = data.db.passwordEntryDao().insertPasswordEntry(entry)
                takagi.ru.monica.repository.CustomFieldRepository(data.db.customFieldDao()).let { repository ->
                    repository.saveFieldsForEntry(id, takagi.ru.monica.data.model.ProjectCredentialGroup.put(emptyList(), row.metadata.forProject(project)))
                }
                entry.copy(id = id)
            }
            shell("ime enable --user $testUser $component"); shell("ime set --user $testUser $component")
            assertEquals(component, shell("settings --user $testUser get secure default_input_method"))
            openFixture("login")
            tap("fixture-field-0")
            val strings = takagi.ru.monica.utils.AppLocaleStringResolver(instrumentation.targetContext)
            tap(strings.get(takagi.ru.monica.R.string.ime_toolbar_autofill))
            clickText("ime-work")
            val work = entries.single { it.username == "ime-work" }
            // Change the persisted value after the candidate was loaded, without opening it again.
            data.db.passwordEntryDao().updatePasswordEntry(work.copy(password = data.security.encryptData("updated-work-secret")))
            clickText(strings.get(takagi.ru.monica.R.string.password))
            waitFor("fixture-field-0") { it.text?.toString() == "updated-work-secret" }
            var staleAction = requireNotNull(textNode(strings.get(takagi.ru.monica.R.string.password)))
            while (!staleAction.isClickable && staleAction.parent != null) staleAction = staleAction.parent
            data.db.passwordEntryDao().updatePasswordEntry(work.copy(isDeleted = true))
            // A refresh may remove the action first. Otherwise a stale click must be rejected.
            staleAction.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.os.SystemClock.sleep(400)
            assertEquals("updated-work-secret", waitFor("fixture-field-0").text?.toString())
        } finally {
            shell("input keyevent KEYCODE_BACK"); shell("input keyevent KEYCODE_BACK")
            shell("ime set --user $testUser $previous")
            if (!wasEnabled) shell("ime disable --user $testUser $component")
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            data.close()
        }
    }

}

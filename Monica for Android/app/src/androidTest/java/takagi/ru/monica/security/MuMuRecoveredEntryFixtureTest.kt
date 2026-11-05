package takagi.ru.monica.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.MonicaAutofillServiceNg
import takagi.ru.monica.autofill_ng.fixture.AutofillFormFixtureActivity
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.ime.ImeUnlockActivity
import takagi.ru.monica.ime.MonicaInputMethodService
import takagi.ru.monica.utils.SettingsManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Operates an existing, host-proven TEE-to-OMK recovered synthetic vault. Never creates a vault,
 * replaces an alias, changes a module or deletes a row. Explicit UI edits remain as evidence.
 */
class MuMuRecoveredEntryFixtureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dao get() = PasswordDatabase.getDatabase(context).passwordEntryDao()
    private val ui: UiAutomation get() = instrumentation.uiAutomation
    private val fixtureRoot get() = File(context.filesDir, "mumu-omk-module-fixture-325")

    @Test fun recoverRetainedVaultAfterActualTransition() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("syntheticOmkUser")
        assumeTrue("Requires a retained synthetic vault", requested != null)
        check(requested!!.toIntOrNull() == Process.myUid() / 100_000 && Process.myUid() / 100_000 > 0)
        val markerFile = File(fixtureRoot, "fixture.json")
        val marker = JSONObject(markerFile.readText())
        check(marker.getInt("uid") == Process.myUid() && marker.getString("packageName") == context.packageName)
        check(marker.getString("phase") == "cold_restart_write_verified" && !marker.getBoolean("usedConfirmedRecovery"))
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs/${SecurePreferencesStore.MONICA}.xml")
        val envelope = File(context.filesDir, "local-vault-recovery/recovery.json")
        val originalPrefs = prefs.readBytes()
        val originalEnvelope = envelope.readBytes()
        val originalRows = listOf("rowId", "newRowId", "coldRowId").associate { key ->
            marker.getLong(key).let { it to checkNotNull(dao.getPasswordEntryById(it)) }
        }
        delay(3_000)
        withTimeout(20_000) { while (SecureStorageStartup.operationInProgress) delay(20) }
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        assertNull(LocalVaultRecovery(context).activeStore())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("secure_startup_blocked").fetchSemanticsNodes(false).isNotEmpty() }
            awaitActivityFocus(scenario)
            assertArrayEquals(originalPrefs, prefs.readBytes())
            assertArrayEquals(originalEnvelope, envelope.readBytes())
            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput(MASTER_PASSWORD)
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed().performClick()
            compose.waitUntil(25_000) {
                LocalVaultRecovery(context).activeStore() != null &&
                    compose.onAllNodesWithTag("secure_startup_blocked").fetchSemanticsNodes(false).isEmpty() &&
                    compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(false).size == 1
            }
            awaitActivityFocus(scenario)
            assertArrayEquals(originalPrefs, prefs.readBytes())
            assertArrayEquals(originalEnvelope, envelope.readBytes())
            assertFalse(SessionManager.isUnlocked.value)
            compose.onNode(hasSetTextAction()).performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
            assertOriginalSecrets(marker, SecurityManager(context))
            for ((id, original) in originalRows) assertEquals(original, dao.getPasswordEntryById(id))
            assertArrayEquals(originalPrefs, prefs.readBytes())
            File(fixtureRoot, "fixture-before-actual-transition.json").apply {
                check(!exists())
                writeText(marker.toString())
            }
            marker.put("usedConfirmedRecovery", true).put("actualTransitionRecoveryProcessId", Process.myPid())
            markerFile.writeText(marker.toString())
        }
        SessionManager.markLocked()
    }

    private suspend fun existingRecoveredVault(): JSONObject {
        val requested = InstrumentationRegistry.getArguments().getString("syntheticOmkUser")
        assumeTrue("Requires an explicitly selected retained synthetic vault", requested != null)
        check(requested!!.toIntOrNull() == Process.myUid() / 100_000 && Process.myUid() / 100_000 > 0)
        val marker = JSONObject(File(fixtureRoot, "fixture.json").readText())
        check(marker.getInt("uid") == Process.myUid() && marker.getString("packageName") == context.packageName)
        check(marker.getString("phase") == "cold_restart_write_verified" && marker.getBoolean("usedConfirmedRecovery"))
        delay(3_000)
        withTimeout(20_000) { while (SecureStorageStartup.operationInProgress) delay(20) }
        assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        assertTrue(security.isMasterPasswordSet())
        assertTrue(security.unlockVaultWithPassword(MASTER_PASSWORD))
        assertOriginalSecrets(marker, security)
        assertNotNull(LocalVaultRecovery(context).activeStore())
        return marker
    }

    private suspend fun assertOriginalSecrets(marker: JSONObject, security: SecurityManager) {
        val row = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
        assertEquals("synthetic-omk-original-user", row.username)
        assertEquals(marker.getString("rowSecret"), security.decryptData(row.password))
        val newRow = checkNotNull(dao.getPasswordEntryById(marker.getLong("newRowId")))
        val coldRow = checkNotNull(dao.getPasswordEntryById(marker.getLong("coldRowId")))
        assertEquals("Synthetic-new-OMK-row-325!Read-Back", security.decryptData(newRow.password))
        assertEquals("Synthetic-cold-OMK-row-325!Read-Back", security.decryptData(coldRow.password))
        assertEquals(marker.getString("mdkPasswordBlob"), security.getProtectedString("mdk_password_blob"))
        assertEquals(marker.getString("protectedSecret"), security.getProtectedString(marker.getString("protectedKey")))
    }

    @Test fun recoveredMainEditsOriginalEntryAndKeepsItsPassword() = runBlocking {
        val marker = existingRecoveredVault()
        val original = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
        val editedTitle = "Synthetic original vault after real UI edit 325 ${java.util.UUID.randomUUID().toString().take(8)}"
        SessionManager.markLocked()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(25_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(false).isNotEmpty() }
            awaitActivityFocus(scenario)
            compose.onNode(hasSetTextAction()).performTextInput(MASTER_PASSWORD)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(25_000) { SessionManager.isUnlocked.value }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(original.title).fetchSemanticsNodes(false).isNotEmpty() ||
                    compose.onAllNodesWithContentDescription(context.getString(R.string.expand)).fetchSemanticsNodes(false).isNotEmpty()
            }
            if (compose.onAllNodesWithContentDescription(context.getString(R.string.expand)).fetchSemanticsNodes(false).isNotEmpty()) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.expand)).onFirst().performClick()
            }
            compose.waitUntil(25_000) { compose.onAllNodesWithText(original.title).fetchSemanticsNodes(false).isNotEmpty() }
            compose.onAllNodesWithText(original.title).onLast().performClick()
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.edit)).fetchSemanticsNodes(false).isNotEmpty() }
            } catch (error: AssertionError) {
                throw AssertionError("Original row opened but edit action missing: " + compose.onRoot(useUnmergedTree = true).printToString(), error)
            }
            compose.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement(editedTitle)
            compose.onNodeWithTag("password_editor_save").performClick()
            withTimeout(25_000) { while (dao.getPasswordEntryById(original.id)?.title != editedTitle) delay(100) }
            assertOriginalSecrets(marker, SecurityManager(context))
            val saved = checkNotNull(dao.getPasswordEntryById(original.id))
            assertEquals(original.website, saved.website)
            assertEquals(original.notes, saved.notes)
            assertTrue(saved.password.startsWith("MDK|"))
            File(fixtureRoot, "main-entry-edit-verified.json").writeText(JSONObject()
                .put("rowId", saved.id).put("title", saved.title).put("passwordPreserved", true)
                .put("processId", Process.myPid()).toString())
        }
        SessionManager.markLocked()
    }

    @Test fun recoveredColdImeEntryAuthenticatesWithOriginalPassword() = runBlocking {
        val marker = existingRecoveredVault()
        val settings = SettingsManager(context)
        val old = settings.settingsFlow.first()
        val received = AtomicReference<Intent?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { received.set(intent) }
        }
        context.registerReceiver(receiver, IntentFilter(MonicaInputMethodService.ACTION_IME_BIOMETRIC_RESULT), Context.RECEIVER_NOT_EXPORTED)
        val oldFlags = ui.serviceInfo.flags
        try {
            settings.updateBiometricEnabled(false)
            settings.updateAutofillKeepUnlocked(false)
            SessionManager.markLocked()
            SecondarySessionManager.markLocked()
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            context.startActivity(Intent(context, ImeUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val input = waitNode { it.packageName?.toString() == context.packageName && it.isEditable }
            assertNull("The IME must require the original password", received.get())
            setText(input, MASTER_PASSWORD)
            tap(waitNode { it.text?.toString() == context.getString(R.string.unlock) })
            withTimeout(25_000) { while (received.get() == null) delay(50) }
            assertTrue(received.get()!!.getBooleanExtra(MonicaInputMethodService.EXTRA_IME_BIOMETRIC_SUCCESS, false))
            assertNull(received.get()!!.getStringExtra(MonicaInputMethodService.EXTRA_IME_BIOMETRIC_ERROR))
            assertFalse("Secondary authentication must not unlock the main process", SessionManager.isUnlocked.value)
            assertTrue(SecurityManager(context).unlockVaultWithPassword(MASTER_PASSWORD))
            assertOriginalSecrets(marker, SecurityManager(context))
        } finally {
            context.unregisterReceiver(receiver)
            settings.updateBiometricEnabled(old.biometricEnabled)
            settings.updateAutofillKeepUnlocked(old.autofillKeepUnlocked)
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            SessionManager.markLocked()
        }
    }

    @Test fun recoveredSystemAutofillSelectsTheOriginalRecord() = runBlocking {
        val marker = existingRecoveredVault()
        val original = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
        val settings = SettingsManager(context)
        val preferences = AutofillPreferences(context)
        val old = settings.settingsFlow.first()
        val enabled = preferences.isAutofillEnabled.first()
        val suggestions = preferences.isPasswordSuggestionEnabled.first()
        val user = Process.myUid() / 100_000
        val previousService = shell("settings --user $user get secure autofill_service").trim()
        check(previousService.isEmpty() || previousService == "null" || previousService.matches(Regex("[A-Za-z0-9_./]+")))
        val oldFlags = ui.serviceInfo.flags
        try {
            ui.serviceInfo = ui.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            settings.updateAutofillKeepUnlocked(false)
            settings.updateAutofillAuthRequired(true)
            settings.updateBiometricEnabled(false)
            preferences.setAutofillEnabled(true)
            preferences.setPasswordSuggestionEnabled(false)
            val service = ComponentName(context, MonicaAutofillServiceNg::class.java).flattenToString()
            shell("settings --user $user put secure autofill_service $service")
            SessionManager.markLocked()
            SecondarySessionManager.markLocked()
            val instance = java.util.UUID.randomUUID().toString()
            context.startActivity(Intent().apply {
                component = ComponentName(instrumentation.context, AutofillFormFixtureActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("scenario", "standard")
                putExtra("fixtureInstance", instance)
                putExtra("expectedUsername", original.username)
                putExtra("expectedPassword", marker.getString("rowSecret"))
            })
            waitNode { it.contentDescription?.toString() == "fixture-instance-$instance" }
            tap(waitNode { it.contentDescription?.toString() == "fixture-field-0" })
            val manual = context.getString(R.string.autofill_manual_entry_title)
            tap(waitNode { it.text?.toString() == manual || it.contentDescription?.toString() == manual })
            compose.waitUntil(25_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput(MASTER_PASSWORD)
            compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithText(original.title).fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithText(original.title).performClick()
            compose.onNodeWithText(context.getString(R.string.autofill)).performClick()
            waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains("user=OK password=OK") == true }
            assertTrue(SecurityManager(context).unlockVaultWithPassword(MASTER_PASSWORD))
            assertOriginalSecrets(marker, SecurityManager(context))
        } finally {
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            if (previousService == "null") shell("settings --user $user delete secure autofill_service")
            else if (previousService.isEmpty()) shell("settings --user $user put secure autofill_service ''")
            else shell("settings --user $user put secure autofill_service $previousService")
            preferences.setAutofillEnabled(enabled)
            preferences.setPasswordSuggestionEnabled(suggestions)
            settings.updateAutofillKeepUnlocked(old.autofillKeepUnlocked)
            settings.updateAutofillAuthRequired(old.autofillAuthRequired)
            settings.updateBiometricEnabled(old.biometricEnabled)
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            SessionManager.markLocked()
        }
    }

    @Test fun recoveredRealKeyboardFillsOriginalRecord() = runBlocking {
        val marker = existingRecoveredVault()
        val original = checkNotNull(dao.getPasswordEntryById(marker.getLong("rowId")))
        val user = Process.myUid() / 100_000
        val component = ComponentName(context, MonicaInputMethodService::class.java).flattenToShortString()
        val settingNames = listOf("default_input_method", "enabled_input_methods", "selected_input_method_subtype")
        val savedSettings = listOf(0, user).associateWith { id -> settingNames.associateWith { name ->
            shell("settings --user $id get secure $name").trim().also {
                check(it.isEmpty() || it.matches(Regex("[A-Za-z0-9_./:;\\-]+")))
            }
        } }
        val oldFlags = ui.serviceInfo.flags
        val settings = SettingsManager(context)
        val old = settings.settingsFlow.first()
        try {
            settings.updateBiometricEnabled(false)
            settings.updateAutofillKeepUnlocked(false)
            SessionManager.markLocked()
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            check(shell("ime list -a -s --user $user").lineSequence().any { it.trim() == component }) {
                "The actual Monica IME must be registered for the retained vault user"
            }
            shell("ime enable --user $user $component")
            shell("ime set --user $user $component")
            assertEquals("Select the exact registered IME ID", component,
                shell("settings --user $user get secure default_input_method").trim())
            val instance = java.util.UUID.randomUUID().toString()
            context.startActivity(Intent().apply {
                setComponent(ComponentName(instrumentation.context, AutofillFormFixtureActivity::class.java))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("scenario", "standard")
                putExtra("accessibilityOnly", true)
                putExtra("fixtureInstance", instance)
                putExtra("expectedUsername", original.username)
                putExtra("expectedPassword", marker.getString("rowSecret"))
            })
            waitNode { it.contentDescription?.toString() == "fixture-instance-$instance" }
            tap(waitNode { it.contentDescription?.toString() == "fixture-field-0" })
            val expectedUid = "u${user}_a${Process.myUid() % 100_000 - 10_000}"
            val process = withTimeout(10_000) {
                var found: List<String>? = null
                while (found == null) {
                    found = shell("ps -A -o USER,NAME").lineSequence().map { it.trim().split(Regex("\\s+")) }
                        .singleOrNull { it.size == 2 && it[0] == expectedUid && it[1] == context.packageName + ":ime" }
                    if (found == null) delay(50)
                }
                checkNotNull(found)
            }
            check(process?.first() == expectedUid) { "IME must belong to the retained vault user, never its parent" }
            val toolbar = waitNode { node ->
                node.packageName?.toString() == context.packageName && node.isEnabled &&
                    node.contentDescription?.toString() == context.getString(R.string.ime_toolbar_autofill) &&
                    ui.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.id == node.windowId }
            }
            assertTrue(toolbar.refresh())
            var toolbarAction = toolbar
            while (!toolbarAction.isClickable) toolbarAction = checkNotNull(toolbarAction.parent)
            assertTrue("Activate the actual keyboard toolbar", toolbarAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val input = waitNode { it.packageName?.toString() == context.packageName && it.isEditable }
            setText(input, MASTER_PASSWORD)
            val hideKeyboard = waitNode { node ->
                node.contentDescription?.toString() == context.getString(R.string.ime_hide_keyboard) &&
                    ui.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.id == node.windowId }
            }
            var hideAction = hideKeyboard
            while (!hideAction.isClickable) hideAction = checkNotNull(hideAction.parent)
            assertTrue(hideAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            tap(waitNode { it.text?.toString() == context.getString(R.string.unlock) })
            val sourceField = waitNode { node ->
                node.contentDescription?.toString() == "fixture-field-0" &&
                    ui.windows.any { it.id == node.windowId && it.isFocused }
            }
            assertTrue(sourceField.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val accountPanel = waitNode { node ->
                node.contentDescription?.toString() == context.getString(R.string.ime_toolbar_autofill) &&
                    ui.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.id == node.windowId }
            }
            var panelAction = accountPanel
            while (!panelAction.isClickable) panelAction = checkNotNull(panelAction.parent)
            assertTrue(panelAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            fun clickImeText(value: String) {
                var forward = true
                fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isScrollable) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { found -> return found } }
                    return null
                }
                val node = waitNode(onMiss = {
                    ui.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .firstNotNullOfOrNull { it.root?.let(::scrollable) }?.let {
                            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            if (!it.performAction(action)) forward = !forward
                        }
                }) { candidate ->
                    candidate.text?.toString() == value && ui.windows.any { window ->
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.id == candidate.windowId
                    }
                }
                var action = node
                while (!action.isClickable) action = checkNotNull(action.parent)
                assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            clickImeText(original.username)
            clickImeText(context.getString(R.string.username))
            tap(waitNode { it.contentDescription?.toString() == "fixture-field-1" })
            clickImeText(context.getString(R.string.password))
            waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains("user=OK password=OK") == true }
            assertTrue(SecurityManager(context).unlockVaultWithPassword(MASTER_PASSWORD))
            assertOriginalSecrets(marker, SecurityManager(context))
        } finally {
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            for (id in listOf(user, 0)) {
                val values = savedSettings.getValue(id)
                val previous = values.getValue("default_input_method")
                if (previous.isNotEmpty() && previous != "null") shell("ime set --user $id $previous")
                for ((name, value) in values) {
                    if (value == "null") shell("settings --user $id delete secure $name")
                    else shell("settings --user $id put secure $name '$value'")
                }
            }
            for ((id, values) in savedSettings) for ((name, value) in values) {
                assertEquals("Restore original IME setting for user $id / $name", value,
                    shell("settings --user $id get secure $name").trim())
            }
            settings.updateBiometricEnabled(old.biometricEnabled)
            settings.updateAutofillKeepUnlocked(old.autofillKeepUnlocked)
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            SessionManager.markLocked()
        }
    }

    private fun <A : android.app.Activity> awaitActivityFocus(scenario: ActivityScenario<A>) {
        compose.waitUntil(10_000) {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
    }

    private fun waitNode(onMiss: (() -> Unit)? = null, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var nextNudge = SystemClock.elapsedRealtime() + 1_000
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            // This process owns a Compose test clock; native node polling alone cannot render recompositions.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            for (window in ui.windows) window.root?.let { find(it)?.let { found -> return found } }
            if (onMiss != null && SystemClock.elapsedRealtime() >= nextNudge) {
                onMiss()
                nextNudge = SystemClock.elapsedRealtime() + 500
            }
            SystemClock.sleep(50)
        }
        fun describe(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 12) return
            if (node.isVisibleToUser && !node.isEditable && node.packageName?.toString() == context.packageName) {
                android.util.Log.i("RetainedEntryFixture", "node class=${node.className} click=${node.isClickable} text=${node.text} description=${node.contentDescription}")
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        ui.windows.forEach { it.root?.let { root -> describe(root) } }
        throw AssertionError("Expected retained-vault entry control was not visible within 25 seconds")
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val rect = Rect().also(node::getBoundsInScreen)
        assertFalse(rect.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(50)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(ui.injectInputEvent(event, action == MotionEvent.ACTION_UP)) } finally { event.recycle() }
        }
    }

    private fun shell(command: String): String {
        // UiAutomation executes argv directly; pass commands through sh stdin so quoted empty values round-trip.
        val streams = ui.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(streams[1]).use {
            it.write((command + "\n").toByteArray(Charsets.UTF_8))
        }
        return ParcelFileDescriptor.AutoCloseInputStream(streams[0]).use { it.bufferedReader().readText() }
    }

    private companion object {
        const val MASTER_PASSWORD = "Synthetic-Real-OMK-325!Only-Test"
    }
}

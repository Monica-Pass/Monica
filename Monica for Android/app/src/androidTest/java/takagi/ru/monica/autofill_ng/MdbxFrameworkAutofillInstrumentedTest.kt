package takagi.ru.monica.autofill_ng

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.autofill_ng.fixture.AutofillFormFixtureActivity
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2NativeRuntime
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.LocaleHelper
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault
import uniffi.mdbx_ffi.MdbxTigaMode as NativeMode

/** Real Android AutofillService -> system suggestion -> callback -> external form.
 * Never launches the callback directly or fabricates a Dataset/NativeAutofillSelection.
 * Synthetic data only, restricted to the root driver's owned secondary user.
 */
class MdbxFrameworkAutofillInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var uiStrings: Context
    private val fixturePackage = instrumentation.context.packageName
    private val user = android.os.Process.myUid() / 100000
    private val database by lazy { PasswordDatabase.getDatabase(context) }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private lateinit var security: SecurityManager
    private lateinit var ui: UiAutomation
    private lateinit var file: File
    private val collectionId = UUID.randomUUID().toString()
    private val objectIds = List(PASSWORDS.size) { UUID.randomUUID().toString() }
    private val suffix = UUID.randomUUID().toString().take(8)
    private var databaseId = 0L
    private var oldSettings: AppSettings? = null
    private var oldScope: AutofillScope? = null
    private var oldEngine: AutofillPreferences.AutofillEngineMode? = null
    private var oldPreferences: List<Boolean>? = null
    private var oldIme: String? = null
    private var oldAutofillService: String? = null
    private var baselinePasswords: List<PasswordEntry>? = null
    private var baselineItems: List<SecureItem>? = null
    private var systemSettingsChanged = false
    private var ownedUserVerified = false
    private var successfulScrollActions = 0

    @Before fun prepare(): Unit = runBlocking {
        check(user > 0 && InstrumentationRegistry.getArguments().getString("autofillBatchUser") == user.toString()) {
            "Requires the explicitly owned secondary user and -e autofillBatchUser <id>"
        }
        ownedUserVerified = true
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        oldAutofillService = Settings.Secure.getString(context.contentResolver, "autofill_service")
        oldSettings = settings.settingsFlow.first()
        // Match the Activity locale without changing the application or system language setting.
        uiStrings = LocaleHelper.setLocale(context, requireNotNull(oldSettings).language)
        android.util.Log.i("AutofillLocale328",
            "target=${context.resources.configuration.locales.toLanguageTags()} " +
                "ui=${uiStrings.resources.configuration.locales.toLanguageTags()} " +
                "language=${requireNotNull(oldSettings).language}")
        oldScope = preferences.v2DefaultScope.first()
        oldEngine = preferences.autofillEngineMode.first()
        oldPreferences = listOf(preferences.isAutofillEnabled.first(), preferences.isFillSuggestionsEnabled.first(),
            preferences.isManualSelectionEnabled.first(), preferences.isPasswordSuggestionEnabled.first(),
            preferences.isRequestSaveDataEnabled.first(), preferences.isOtpNotificationEnabled.first(),
            preferences.isAutoCopyOtpEnabled.first(), preferences.isInlineSuggestionsEnabled.first())
        baselinePasswords = database.passwordEntryDao().getAllPasswordEntriesSync()
        baselineItems = database.secureItemDao().getAllItemsIncludingArchived().first()
        // Do not make unrelated native libraries part of this fixture's system request.
        check(database.localMdbxDatabaseDao().getAllDatabases().first().isEmpty())
        check(database.localKeePassDatabaseDao().getAllDatabases().first().isEmpty())
        check(database.bitwardenVaultDao().getAllVaults().isEmpty())
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(MASTER_PASSWORD))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(MASTER_PASSWORD)) { "Unexpected synthetic vault password" }
        assertFalse(SessionManager.isUnlocked.value)
        settings.updateBiometricEnabled(false)
        settings.updateAutofillAuthRequired(true)
        settings.updateAutofillKeepUnlocked(true)
        settings.updateScreenshotProtectionEnabled(false)
        settings.updateAutoLockMinutes(5)
        SessionManager.updateAutoLockTimeout(5)
        preferences.setAutofillEnabled(true)
        preferences.setFillSuggestionsEnabled(true)
        preferences.setManualSelectionEnabled(true)
        preferences.setPasswordSuggestionEnabled(false)
        preferences.setRequestSaveDataEnabled(false)
        preferences.setOtpNotificationEnabled(false)
        preferences.setAutoCopyOtpEnabled(false)
        preferences.setInlineSuggestionsEnabled(false)
        preferences.setAutofillEngineMode(AutofillPreferences.AutofillEngineMode.BITWARDEN_V2)
        AutofillSessionGrants.clear()
        Mdbx2NativeRuntime.ensureLoaded()
        file = File(context.cacheDir, "mdbx-framework-328-${UUID.randomUUID()}.mdbx")
        val vault = createVaultWithTigaMode(file.path, VAULT_PASSWORD, "framework328-fixture", NativeMode.SKY)
        try {
            vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-framework-fixture",
                listOf(MdbxWriteCommand.CreateProject(collectionId, "Synthetic framework collection")) +
                    objectIds.mapIndexed { index, id ->
                        MdbxWriteCommand.CreateEntry(id, collectionId, "login", title(index), payload(index))
                    })
        } finally { vault.close() }
        databaseId = database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
            name = "MDBX framework $suffix", filePath = file.path,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
            encryptedPassword = security.encryptData(VAULT_PASSWORD)))
        preferences.setV2DefaultScope(AutofillScope.Mdbx(databaseId))
        assertNoRoomChanges()
    }

    @After fun cleanup(): Unit = runBlocking {
        if (!ownedUserVerified) return@runBlocking
        if (::ui.isInitialized) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filter { it is AutofillPickerActivityV2 || it is AutofillCipherCallbackActivity }
                    .forEach { it.finish() }
            }
        }
        if (databaseId != 0L) {
            val registered = database.localMdbxDatabaseDao().getDatabaseById(databaseId)
            check(registered?.filePath == file.path) { "Owned registration unexpectedly changed" }
            Mdbx2NativeReadSessions.invalidate(databaseId)
            database.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
        }
        if (::file.isInitialized) {
            check(file.canonicalFile.parentFile == context.cacheDir.canonicalFile && file.name.startsWith("mdbx-framework-328-"))
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
                .forEach { if (it.exists()) check(it.delete()) { "Owned native fixture cleanup failed" } }
        }
        oldSettings?.let {
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateAutofillKeepUnlocked(it.autofillKeepUnlocked)
            settings.updateScreenshotProtectionEnabled(it.screenshotProtectionEnabled)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            SessionManager.updateAutoLockTimeout(it.autoLockMinutes)
        }
        oldPreferences?.let {
            preferences.setAutofillEnabled(it[0]); preferences.setFillSuggestionsEnabled(it[1])
            preferences.setManualSelectionEnabled(it[2]); preferences.setPasswordSuggestionEnabled(it[3])
            preferences.setRequestSaveDataEnabled(it[4]); preferences.setOtpNotificationEnabled(it[5])
            preferences.setAutoCopyOtpEnabled(it[6]); preferences.setInlineSuggestionsEnabled(it[7])
        }
        oldEngine?.let { preferences.setAutofillEngineMode(it) }
        oldScope?.let { preferences.setV2DefaultScope(it) }
        if (systemSettingsChanged) {
            restoreSecureSetting("autofill_service", oldAutofillService)
            if (!oldIme.isNullOrBlank()) {
                shell("ime set --user $user ${safeComponent(requireNotNull(oldIme))}")
                assertEquals(oldIme, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            } else restoreSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD, oldIme)
        }
        assertNoRoomChanges()
        AutofillSessionGrants.clear()
        SessionManager.markLocked()
    }

    @Test fun frameworkSuggestionsOpenRealMdbxCallbacksAndFillLiteralValues(): Unit = runBlocking {
        val original = nativeState()
        enableFramework()
        authenticatePickerForFixture()
        PASSWORDS.indices.forEach { index ->
            val callback = instrumentation.addMonitor(AutofillCipherCallbackActivity::class.java.name, null, false)
            try {
                openForm(index)
                focusForm()
                tapFrameworkSuggestion(title(index))
                expectForm("user=OK password=OK")
                assertCreatedNativeCallback(callback, objectIds[index])
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "MDBX328 framework suggestion=$index callback=observed user=OK password=OK\n")
                })
            } finally { instrumentation.removeMonitor(callback) }
        }
        assertEquals("Autofill must not modify native objects", original, nativeState())
        assertFalse(SessionManager.isUnlocked.value)
        assertNoRoomChanges()
    }

    @Test fun cachedFrameworkSuggestionRejectsExternalNativeRevision(): Unit = runBlocking {
        enableFramework()
        authenticatePickerForFixture()
        val callback = instrumentation.addMonitor(AutofillCipherCallbackActivity::class.java.name, null, false)
        val picker = instrumentation.addMonitor(AutofillPickerActivityV2::class.java.name, null, false)
        try {
            openForm(0)
            focusForm()
            // Observe the real framework suggestion before changing its selected native revision.
            waitNode(scroll = true) { it.text?.toString() == title(0) || it.contentDescription?.contains(title(0)) == true }
            val before = nativeState()
            val vault = openVault(file.path, VAULT_PASSWORD, "framework328-external-writer")
            try {
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-external-revision", listOf(
                    MdbxWriteCommand.UpdateEntry(objectIds[0], collectionId, "login", title(0),
                        payload(0, "Changed-native-value-328!"))))
            } finally { vault.close() }
            val after = nativeState()
            assertNotEquals("The negative control must change the native revision", before, after)
            tapFrameworkSuggestion(title(0))
            assertNotNull("A stale native callback must request a fresh picker selection",
                instrumentation.waitForMonitorWithTimeout(picker, 10_000))
            assertCreatedNativeCallback(callback, objectIds[0])
            waitNode { it.packageName?.toString() == context.packageName && it.text?.toString() == title(0) }
            hideResumedPickerIme()
            assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            expectForm("user=EMPTY password=EMPTY")
            assertEquals("Rejecting a stale suggestion must preserve the external edit", after, nativeState())
        } finally {
            instrumentation.removeMonitor(picker)
            instrumentation.removeMonitor(callback)
        }
        assertFalse(SessionManager.isUnlocked.value)
        assertNoRoomChanges()
    }

    @Test fun cancellingFrameworkMdbxPickerAuthenticationLeavesFormEmpty(): Unit = runBlocking {
        val original = nativeState()
        enableFramework()
        openForm(0)
        focusForm()
        tap(waitText(uiStrings.getString(R.string.autofill_manual_entry_title)))
        waitNode { it.packageName?.toString() == context.packageName && it.isEditable && it.isPassword }
        awaitFocusedPasswordIme()
        hideResumedPickerIme()
        tap(waitText(uiStrings.getString(R.string.cancel)))
        expectForm("user=EMPTY password=EMPTY")
        assertFalse(AutofillSessionGrants.isGranted(AutofillGrantContext(fixturePackage, null, null, null)))
        assertFalse(SessionManager.isUnlocked.value)
        assertEquals(original, nativeState())
        assertNoRoomChanges()
    }

    private fun authenticatePickerForFixture() {
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerIntent()).use {
            verifyMasterPassword()
            assertTrue("Only real successful picker verification grants the external form",
                AutofillSessionGrants.isGranted(AutofillGrantContext(fixturePackage, null, null, null)))
        }
    }

    private fun assertCreatedNativeCallback(monitor: android.app.Instrumentation.ActivityMonitor, objectId: String) {
        // ActivityMonitor.hits counts intercepted execStartActivity calls. System PendingIntent
        // launches arrive through Activity creation instead, which sets lastActivity but not hits.
        // Await the actual created instance; a fresh monitor is used for every framework request.
        val callback = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
        assertTrue("System suggestion must create the actual cipher callback",
            callback is AutofillCipherCallbackActivity)
        val bundle = requireNotNull(callback?.intent?.getBundleExtra("extra_args_bundle")) {
            "The actual system callback must retain its framework native-selection bundle"
        }
        assertEquals("MDBX", bundle.getString("native_storage"))
        assertEquals(databaseId, bundle.getLong("native_database_id"))
        assertEquals(objectId, bundle.getString("native_entry_uuid"))
        assertEquals(AutofillPasswordSource.mdbxId(databaseId, objectId), bundle.getLong("password_id"))
    }

    private fun nativeState(): List<Pair<String, String>> {
        val vault = openVault(file.path, VAULT_PASSWORD, "framework328-audit")
        return try {
            objectIds.map { id ->
                val summary = requireNotNull(vault.getObjectSummary(id))
                val record = requireNotNull(vault.revealObjectWithLimits(id, MdbxObjectDisclosureLimits(1_048_576uL)).`object`)
                check(record.objectId == id && record.collectionId == collectionId && !record.deleted)
                summary.headCommitId to record.payloadJson
            }
        } finally { vault.close() }
    }

    private suspend fun assertNoRoomChanges() {
        baselinePasswords?.let { assertEquals("Native framework fill must preserve all Room passwords", it,
            database.passwordEntryDao().getAllPasswordEntriesSync()) }
        baselineItems?.let { assertEquals("Native framework fill must preserve all Room items", it,
            database.secureItemDao().getAllItemsIncludingArchived().first()) }
    }

    private fun payload(index: Int, password: String = PASSWORDS[index]) = buildJsonObject {
        put("username", username(index)); put("password_plain", password)
        put("app_package_name", fixturePackage); put("website", "https://mdbx-framework.example")
    }.toString()

    private fun pickerIntent() = AutofillPickerActivityV2.getIntent(context,
        AutofillPickerActivityV2.Args(applicationId = fixturePackage))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        .putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)

    // UI helpers below are copied from the existing real KDBX framework driver.
    private fun verifyMasterPassword() {
        val field = waitNode { it.isEditable && it.isPassword }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, MASTER_PASSWORD)
        }))
        hideResumedPickerIme()
        tap(waitText(uiStrings.getString(R.string.confirm)))
        waitText(title(0))
        assertFalse("Autofill verification must keep main session locked", SessionManager.isUnlocked.value)
    }


    private fun awaitFocusedPasswordIme() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            val focused = nodes().any { it.packageName?.toString() == context.packageName &&
                it.isVisibleToUser && it.isEditable && it.isPassword && it.isFocused }
            var imeVisible = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AutofillPickerActivityV2>()
                    .firstOrNull { !it.isFinishing && it.hasWindowFocus() }?.let { picker ->
                        imeVisible = ViewCompat.getRootWindowInsets(picker.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
            }
            // The password card requests focus and shows the IME after its first composition.
            // Wait for that actual state before hiding it so a pending show cannot cover Cancel.
            if (focused && imeVisible) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Password field must be focused with its IME visible before cancellation")
    }

    private fun hideResumedPickerIme() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            var ready = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AutofillPickerActivityV2>()
                    .firstOrNull { !it.isFinishing && it.hasWindowFocus() }?.let { picker ->
                        val root = picker.window.decorView
                        WindowCompat.getInsetsController(picker.window, root).hide(WindowInsetsCompat.Type.ime())
                        ready = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                    }
            }
            if (ready) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Resumed picker must retain focus with its IME hidden before authentication action")
    }

    private fun enableFramework() {
        systemSettingsChanged = true
        shell("settings --user $user put secure autofill_service ${safeComponent(context.packageName + "/" + MonicaAutofillServiceNg::class.java.name)}")
        val ime = "$fixturePackage/takagi.ru.monica.autofill_ng.fixture.AutofillFixtureInputMethodService"
        shell("ime enable --user $user ${safeComponent(ime)}")
        shell("ime set --user $user ${safeComponent(ime)}")
        assertEquals(ime, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
    }

    private fun openForm(index: Int) {
        val instance = UUID.randomUUID().toString()
        context.startActivity(Intent().apply {
            component = ComponentName(fixturePackage, AutofillFormFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("scenario", "standard"); putExtra("fixtureInstance", instance)
            putExtra("expectedUsername", username(index)); putExtra("expectedPassword", PASSWORDS[index])
        })
        waitNode { it.contentDescription?.toString() == "fixture-instance-$instance" }
    }
    private fun focusForm() = tap(waitNode { it.contentDescription?.toString() == "fixture-field-0" })
    private fun expectForm(expected: String) = waitNode {
        it.contentDescription?.toString() == "fixture-status" && it.text?.toString()?.contains(expected) == true
    }
    private fun waitText(value: String) = waitNode { it.text?.toString() == value || it.contentDescription?.toString() == value }


    private fun nodes(): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        ui.rootInActiveWindow?.let(queue::add)
        ui.windows.mapNotNull { it.root }.forEach(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && result.size < 700) {
            val node = queue.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return result
    }
    private fun waitNode(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var scrollAttempts = 0
        do {
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            val visible = nodes().filter { it.refresh() && it.isVisibleToUser }
            visible.firstOrNull(predicate)?.let { return it }
            if (scroll && scrollAttempts < 8) {
                // The framework dropdown is shorter than four rows on MuMu. Scroll its actual
                // accessible list only while the requested row is outside the visible viewport.
                visible.firstOrNull { it.isScrollable && it.packageName?.toString() != fixturePackage }?.let { target ->
                    scrollAttempts++
                    try {
                        ui.executeAndWaitForEvent({
                            if (target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) successfulScrollActions++
                        }, { event -> event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED }, 1_000).recycle()
                    } catch (_: TimeoutException) {
                        // Some framework surfaces omit the event; the next node snapshot is decisive.
                    }
                }
            }
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        // Log structure and synthetic labels only; never app field values from unrelated packages.
        val visible = nodes().filter { it.packageName?.toString() in setOf(context.packageName, fixturePackage) }
            .filterNot { it.isPassword }.map { "${it.className}|${it.text}|${it.contentDescription}" }
        throw AssertionError("Expected MDBX328 UI not visible: ${visible.joinToString("\n")}")
    }

    private fun tapFrameworkSuggestion(title: String) {
        fun matches(node: AccessibilityNodeInfo) =
            node.text?.toString() == title || node.contentDescription?.contains(title) == true
        val initial = waitNode(scroll = true, predicate = ::matches)
        val windowId = initial.windowId
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastBounds: Rect? = null
        var stableFrames = 0
        do {
            // A scroll event reports motion beginning, not completion. Observe fresh frames
            // until the actual system row stops moving before invoking its click action.
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
            check(frame.await(1, TimeUnit.SECONDS)) { "No UI frame while waiting for suggestion bounds" }
            val node = nodes().firstOrNull { it.windowId == windowId && it.refresh() && it.isVisibleToUser && matches(it) }
            val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            val windowBounds = ui.windows.firstOrNull { it.id == windowId }?.let { Rect().also(it::getBoundsInScreen) }
            if (node == null || bounds == null || bounds.isEmpty || windowBounds?.contains(bounds) != true) {
                stableFrames = 0
                lastBounds = null
                continue
            }
            stableFrames = if (bounds == lastBounds) stableFrames + 1 else 0
            lastBounds = bounds
            if (stableFrames < 3) continue
            var clickTarget: AccessibilityNodeInfo? = node
            while (clickTarget != null && !clickTarget.isClickable && clickTarget.windowId == windowId) {
                clickTarget = clickTarget.parent
            }
            if (clickTarget != null && clickTarget.windowId == windowId && clickTarget.isVisibleToUser && clickTarget.isEnabled) {
                check(clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System suggestion rejected its click action" }
            } else tap(node)
            return
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("System suggestion did not settle inside its visible window: $title")
    }


    private fun tap(node: AccessibilityNodeInfo) {
        check(node.refresh() && node.isVisibleToUser && node.isEnabled) { "Tap target is no longer visible/enabled" }
        val rect = Rect().also(node::getBoundsInScreen)
        check(!rect.isEmpty) { "Tap target has empty bounds" }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(60)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(event, action == MotionEvent.ACTION_UP)) } finally { event.recycle() }
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .use { it.bufferedReader().readText() }
    private fun safeComponent(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_./:$]+")))
        return value
    }
    private fun restoreSecureSetting(name: String, value: String?) {
        require(name in setOf("autofill_service", Settings.Secure.DEFAULT_INPUT_METHOD))
        // Preserve null and empty settings exactly; they are not service component names.
        ui.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(context.contentResolver, name, value))
            assertEquals("Restore original $name", value, Settings.Secure.getString(context.contentResolver, name))
        } finally { ui.dropShellPermissionIdentity() }
    }



    private fun title(index: Int) = "MDBX framework $suffix login $index"
    private fun username(index: Int) = "C2|mdbx-native-user-$index"

    companion object {
        private const val MASTER_PASSWORD = "Batch328-synthetic-master!"
        private const val VAULT_PASSWORD = "Mdbx-framework328-synthetic!"
        private val PASSWORDS = listOf("MDK|literal-mdbx-framework-328", "C2|literal-mdbx-framework-328")
    }
}

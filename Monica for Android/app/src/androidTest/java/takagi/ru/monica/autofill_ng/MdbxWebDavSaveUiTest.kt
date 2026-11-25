package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.mdbx2PhysicalEntryId
import takagi.ru.monica.repository.mdbxPasswordObjectId
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits
import uniffi.mdbx_ffi.MdbxObjectSummary
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.openVault
import java.util.UUID

/** New-editor writes and callbacks through the actual V2 Activity, against a synthetic native vault. */
class MdbxWebDavSaveUiTest : MdbxAutofillFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private val appDb by lazy { PasswordDatabase.getDatabase(context) }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private val suffix = UUID.randomUUID().toString().take(8)
    private val title = "SameTitle-$suffix"
    private val username = "same-account-$suffix"
    private val newPassword = "New328-synthetic-$suffix!"
    private val vaultName = "SaveTarget-$suffix"
    private var oldSettings: AppSettings? = null
    private var oldScope: AutofillScope? = null
    private var baselineRows: List<PasswordEntry>? = null
    private var cloud: MdbxWebDavCloudFixture? = null
    private lateinit var native: Fixture
    private lateinit var decoyBefore: Pair<MdbxObjectSummary?, String>
    private val scenarios = mutableListOf<ActivityScenario<out Activity>>()

    @Before fun prepareSaveUi(): Unit = runBlocking {
        check(appDb.localMdbxDatabaseDao().getAllDatabases().first().isEmpty())
        check(appDb.localKeePassDatabaseDao().getAllDatabases().first().isEmpty())
        check(appDb.bitwardenVaultDao().getAllVaults().isEmpty())
        baselineRows = appDb.passwordEntryDao().getAllPasswordEntriesSync()
        oldSettings = settings.settingsFlow.first()
        oldScope = preferences.v2DefaultScope.first()
        settings.updateBiometricEnabled(false)
        settings.updateAutofillAuthRequired(true)
        settings.updateAutofillKeepUnlocked(false)
        settings.updateAutoLockMinutes(5)
        SessionManager.updateAutoLockTimeout(5)
        preferences.setV2DefaultScope(AutofillScope.Local)
        val payload = buildJsonObject {
            put("username", username); put("password_plain", "existing-decoy-secret")
            put("website", "https://native.example")
        }.toString()
        val isolated = fixture(payload = payload)
        val vault = openVault(isolated.file.path, vaultPassword, "synthetic-save-setup")
        try { vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-decoy", listOf(
            MdbxWriteCommand.UpdateEntry(isolated.objectId, isolated.folderId, "login", title, payload))) }
        finally { vault.close() }
        val registration = requireNotNull(database.localMdbxDatabaseDao().getDatabaseById(isolated.databaseId))
        val id = appDb.localMdbxDatabaseDao().insertDatabase(registration.copy(id = 0L, name = vaultName,
            filePath = "synthetic/vault.mdbx", workingCopyPath = isolated.file.path,
            sourceType = takagi.ru.monica.data.MdbxSourceType.REMOTE_WEBDAV.name,
            storageLocation = takagi.ru.monica.data.MdbxStorageLocation.REMOTE_WEBDAV.name))
        native = isolated.copy(databaseId = id)
        cloud = MdbxWebDavCloudFixture.optional(context, appDb, security)
        cloud?.prepare(id)
        decoyBefore = decoySnapshot()
        AutofillSessionGrants.clear()
    }

    @After fun cleanupSaveUi(): Unit = runBlocking {
        if (oldSettings == null) return@runBlocking
        scenarios.asReversed().forEach { it.close() }
        AutofillSessionGrants.clear()
        check(security.unlockVaultWithPassword(MASTER))
        try { cloud?.close() } finally {
        if (::native.isInitialized) {
            appDb.passwordEntryDao().getAllPasswordEntriesSync()
                .filter { it.mdbxDatabaseId == native.databaseId }
                .forEach { appDb.passwordEntryDao().deletePasswordEntryById(it.id) }
            appDb.localMdbxDatabaseDao().deleteDatabaseById(native.databaseId)
        }
        oldScope?.let { preferences.setV2DefaultScope(it) }
        oldSettings?.let {
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateAutofillKeepUnlocked(it.autofillKeepUnlocked)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            SessionManager.updateAutoLockTimeout(it.autoLockMinutes)
        }
        baselineRows?.let { assertEquals("Preserve all pre-existing Room rows", it,
            appDb.passwordEntryDao().getAllPasswordEntriesSync()) }
        }
    }

    @Test fun savingToMdbxReturnsTheNewNativeObjectDespiteSameTitleAndAccount() {
        val (scenario, targets) = launchEditor()
        selectMdbxTarget(scenario)
        compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
        waitUntil("Native save callback must return an actual Dataset") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertEquals(listOf(username, newPassword), datasetValues(scenario, targets))
        val created = runBlocking { appDb.passwordEntryDao().getAllPasswordEntriesSync() }
            .filter { it.id !in baselineRows.orEmpty().map(PasswordEntry::id) }
        assertEquals("Saving to one selected target creates exactly one owned Room row", 1, created.size)
        val row = created.single()
        assertTrue(row.id > 0L)
        assertEquals(native.databaseId, row.mdbxDatabaseId)
        assertNull(row.keepassDatabaseId)
        assertNull(row.bitwardenVaultId)
        val logical = mdbxPasswordObjectId(row)
        assertEquals(logical, row.replicaGroupId)
        val vault = openVault(native.file.path, vaultPassword, "synthetic-save-verification")
        try {
            val physical = logical
            assertNotEquals("A same-title record must not be reused", native.objectId, physical)
            val summary = requireNotNull(vault.getObjectSummary(physical))
            assertEquals("login", summary.objectTypeId)
            assertEquals(title, summary.title)
            val record = requireNotNull(vault.revealObjectWithLimits(physical,
                MdbxObjectDisclosureLimits(1024uL * 1024uL)).`object`)
            val payload = JSONObject(record.payloadJson)
            assertEquals(username, payload.getString("username"))
            assertEquals(newPassword, payload.getString("password_plain"))
        } finally { vault.close() }
        assertEquals(decoyBefore, decoySnapshot())
        runBlocking { cloud?.verifyDelivery(row, newPassword, vaultPassword) }
        assertFalse("Autofill save must not unlock main application", SessionManager.isUnlocked.value)
    }

    @Test fun cancellingMdbxEditorReturnsCancelledAndKeepsNativeAndRoomData() {
        val (scenario, _) = launchEditor()
        selectMdbxTarget(scenario)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitText(text(R.string.filter_title))
        compose.onNodeWithText(text(R.string.close)).performClick()
        waitUntil("Cancelled editor and picker must close") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals(baselineRows, runBlocking { appDb.passwordEntryDao().getAllPasswordEntriesSync() })
        assertEquals(decoyBefore, decoySnapshot())
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun transparentSaveAuthenticatesIndependentlyAndLeavesWebDavEditPending() {
        val scenario = launchTransparent()
        selectMdbxTarget(scenario)
        assertFalse("Authentication is specific to this autofill request", SessionManager.isUnlocked.value)
        compose.onNodeWithTag("password_editor_save").performClick()
        waitUntil("Authenticated transparent save must finish") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(AutofillSaveTransparentActivity.RESULT_SAVED, scenario.result.resultCode)
        val row = runBlocking { appDb.passwordEntryDao().getByMdbxDatabaseIdSync(native.databaseId) }.single()
        val vault = openVault(native.file.path, vaultPassword, "transparent-independent-reader")
        try {
            val payload = JSONObject(requireNotNull(vault.revealObjectWithLimits(requireNotNull(row.replicaGroupId),
                MdbxObjectDisclosureLimits(1024uL * 1024uL)).`object`).payloadJson)
            assertEquals(username, payload.getString("username"))
            assertEquals(newPassword, payload.getString("password_plain"))
        } finally { vault.close() }
        assertEquals(takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name,
            runBlocking { appDb.localMdbxDatabaseDao().getDatabaseById(native.databaseId) }!!.lastSyncStatus)
        assertEquals(decoyBefore, decoySnapshot())
        runBlocking { cloud?.verifyDelivery(row, newPassword, vaultPassword) }
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun transparentWrongPasswordAndCancelDoNotWrite() {
        val scenario = launchTransparent(authenticate = false)
        compose.onNode(hasSetTextAction()).performTextReplacement("Wrong-synthetic-password")
        hideIme(scenario)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitText(text(R.string.password_incorrect))
        compose.onAllNodesWithTag("password_editor_save").assertCountEquals(0)
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        waitUntil("Cancelled authentication must close") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals(decoyBefore, decoySnapshot())
        assertTrue(runBlocking { appDb.passwordEntryDao().getByMdbxDatabaseIdSync(native.databaseId) }.isEmpty())
    }

    @Test fun transparentLockBeforeSaveRevokesThePage() {
        val scenario = launchTransparent()
        selectMdbxTarget(scenario)
        compose.runOnIdle { SessionManager.markLocked() }
        waitUntil("Explicit lock must close the save request") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals(decoyBefore, decoySnapshot())
        assertTrue(runBlocking { appDb.passwordEntryDao().getByMdbxDatabaseIdSync(native.databaseId) }.isEmpty())
    }

    @Test fun transparentBackgroundBeforeSaveRevokesThePage() {
        val scenario = launchTransparent()
        selectMdbxTarget(scenario)
        var taskId = -1
        scenario.onActivity { taskId = it.taskId; it.moveTaskToBack(true) }
        waitUntil("Backgrounding must revoke the save request") { scenario.state == Lifecycle.State.DESTROYED }
        // Android delivers the result when the test's parent task resumes.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am task focus $taskId").close()
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals(decoyBefore, decoySnapshot())
        assertTrue(runBlocking { appDb.passwordEntryDao().getByMdbxDatabaseIdSync(native.databaseId) }.isEmpty())
    }

    @Test fun transparentUpdatePreservesNativeIdAndFutureFields() = runBlocking {
        val vault = openVault(native.file.path, vaultPassword, "synthetic-update-setup")
        try {
            val payload = JSONObject(decoyBefore.second).put("app_package_name", "org.example.mdbxsave331")
                .put("future", JSONObject().put("text", "keep-future-data")).toString()
            vault.executeWriteOperation(UUID.randomUUID().toString(), "update-fixture", listOf(
                MdbxWriteCommand.UpdateEntry(native.objectId, native.folderId, "login", title, payload)))
        } finally { vault.close() }
        val originalId = appDb.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = title,
            website = "https://native.example", username = username, password = security.encryptData("existing-decoy-secret"),
            appPackageName = "org.example.mdbxsave331", mdbxDatabaseId = native.databaseId,
            mdbxFolderId = native.folderId, replicaGroupId = native.objectId))
        val scenario = launchTransparent(selectExisting = true)
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_value_0") and hasText(newPassword)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_save").performClick()
        waitUntil("Existing native password must update") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(AutofillSaveTransparentActivity.RESULT_SAVED, scenario.result.resultCode)
        val row = appDb.passwordEntryDao().getByMdbxDatabaseIdSync(native.databaseId).single()
        assertEquals(originalId, row.id)
        assertEquals(native.objectId, row.replicaGroupId)
        val payload = JSONObject(decoySnapshot().second)
        assertEquals(newPassword, payload.getString("password_plain"))
        assertEquals("keep-future-data", payload.getJSONObject("future").getString("text"))
        cloud?.verifyDelivery(row, newPassword, vaultPassword)
        assertFalse(SessionManager.isUnlocked.value)
    }

    private fun launchTransparent(authenticate: Boolean = true, selectExisting: Boolean = false): ActivityScenario<AutofillSaveTransparentActivity> {
        val intent = android.content.Intent(context, AutofillSaveTransparentActivity::class.java).apply {
            putExtra(AutofillSaveTransparentActivity.EXTRA_USERNAME, username)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD, newPassword)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PACKAGE_NAME, "org.example.mdbxsave331")
        }
        val scenario = ActivityScenario.launchActivityForResult<AutofillSaveTransparentActivity>(intent)
        scenarios += scenario
        compose.waitUntil(15000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        if (!authenticate) return scenario
        compose.onNode(hasSetTextAction()).performTextReplacement(MASTER)
        hideIme(scenario)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        if (selectExisting) {
            waitText(text(R.string.autofill_save_review_title))
            compose.onNodeWithText(title).performClick()
        }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("password_editor_heading").fetchSemanticsNodes().isNotEmpty() }
        if (!selectExisting) compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement(title)
        hideIme(scenario)
        return scenario
    }

    private fun launchEditor(): Pair<ActivityScenario<AutofillPickerActivityV2>, List<AutofillId>> {
        val targets = compose.runOnUiThread {
            arrayListOf(android.widget.EditText(context).autofillId, android.widget.EditText(context).autofillId)
        }
        assertNotEquals(targets[0], targets[1])
        val args = AutofillPickerActivityV2.Args(applicationId = "org.example.mdbxsave328",
            webDomain = "native.example", interactionIdentifier = "save-native-${UUID.randomUUID()}",
            capturedUsername = username, capturedPassword = newPassword, autofillIds = targets,
            autofillHints = arrayListOf("USERNAME", "PASSWORD"), rememberLastFilled = false)
        val scenario = ActivityScenario.launchActivityForResult<AutofillPickerActivityV2>(AutofillPickerActivityV2.getIntent(context, args))
        scenarios += scenario
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(MASTER)
        hideIme(scenario)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitText(text(R.string.filter_title))
        compose.onNodeWithContentDescription(text(R.string.add)).performClick()
        compose.onNode(hasText(text(R.string.item_type_password)) and hasClickAction()).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("password_editor_heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement(title)
        compose.onNode(hasText(text(R.string.username)) and hasSetTextAction()).performScrollTo().performTextReplacement(username)
        compose.onNodeWithTag("password_editor_value_0").performScrollTo().performTextReplacement(newPassword)
        hideIme(scenario)
        return scenario to targets
    }

    private fun selectMdbxTarget(scenario: ActivityScenario<out Activity>) {
        compose.onNodeWithText(text(R.string.multi_storage_title)).performScrollTo().performClick()
        waitText(text(R.string.vault_select_storage))
        compose.onNode(hasText(vaultName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick().assertIsSelected()
        Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.vault_select_storage)).fetchSemanticsNodes().isEmpty() }
        // The selector's visible summary confirms the editor now owns only this MDBX target.
        compose.onNode(hasText(vaultName, substring = true)).assertExists()
        hideIme(scenario)
    }

    private fun hideIme(scenario: ActivityScenario<out Activity>) {
        scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
    }

    private fun datasetValues(scenario: ActivityScenario<AutofillPickerActivityV2>, targets: List<AutofillId>): List<String> {
        @Suppress("DEPRECATION")
        val dataset = requireNotNull(scenario.result.resultData.getParcelableExtra<Dataset>(AutofillManager.EXTRA_AUTHENTICATION_RESULT))
        @Suppress("UNCHECKED_CAST")
        val ids = Dataset::class.java.getMethod("getFieldIds").invoke(dataset) as List<AutofillId>
        @Suppress("UNCHECKED_CAST")
        val values = Dataset::class.java.getMethod("getFieldValues").invoke(dataset) as List<AutofillValue?>
        assertEquals(targets.size, ids.size)
        assertEquals(targets.toSet(), ids.toSet())
        val actual = ids.zip(values).associate { (id, value) ->
            val typed = requireNotNull(value)
            assertTrue(typed.isText)
            id to typed.textValue.toString()
        }
        return targets.map(actual::getValue)
    }

    private fun decoySnapshot(): Pair<MdbxObjectSummary?, String> {
        val vault = openVault(native.file.path, vaultPassword, "synthetic-decoy-verification")
        return try { vault.getObjectSummary(native.objectId) to requireNotNull(vault.revealObjectWithLimits(native.objectId,
            MdbxObjectDisclosureLimits(1024uL * 1024uL)).`object`).payloadJson }
        finally { vault.close() }
    }

    private fun text(id: Int) = context.getString(id)
    private fun waitText(value: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(value).assertIsDisplayed()
    }
    private fun waitUntil(reason: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20_000
        do {
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return
            Thread.sleep(20)
        } while (SystemClock.elapsedRealtime() < until)
        throw AssertionError(reason)
    }

    companion object { private const val MASTER = "Batch328-synthetic-master!" }
}

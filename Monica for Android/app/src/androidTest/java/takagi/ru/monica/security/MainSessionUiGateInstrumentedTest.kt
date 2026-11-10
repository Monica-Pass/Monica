package takagi.ru.monica.security

import android.content.Intent
import android.database.Cursor
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

/**
 * Focused regression for the retained synthetic upgrade corpus.
 * Read-only with respect to the existing corpus. Never creates/resets a vault.
 * Temporary app settings are restored as the exact original Preferences snapshot.
 * Host must run exclusively on the already established toolchain corpus in user 12.
 *
 * Does NOT claim deterministic delayed-startup publication or a failed Keystore read.
 * The third method covers the actual bypass UI while the shared session is locked.
 */
class MainSessionUiGateInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var fixture: LegacyVaultCorpus
    private lateinit var settings: SettingsManager
    private lateinit var settingsStore: DataStore<Preferences>
    private var originalSettings: Preferences? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalRows: String? = null
    private var markerDigest: String? = null
    private var originalAliases: Set<String>? = null
    private var expectedTitle = ""
    private var expectedRowId = -1L
    private var settingsTouched = false
    private var originalAutoLockMinutes = 5

    @Before fun requireExistingOwnedCorpus() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(Process.myUid() / 100_000 == 12) { "This test is restricted to the retained toolchain corpus in user 12" }
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("mainSessionUiUser") == "yes")
        check(args.getString("isolatedRecoveryUser") == "yes")
        val expectedApk = checkNotNull(args.getString("toolchainTargetApkSha256"))
        check(expectedApk.matches(Regex("[0-9a-f]{64}")))
        check(expectedApk == digest(File(context.applicationInfo.sourceDir))) { "Unexpected installed target APK" }

        // Check persisted ownership before constructing any fixture SecurityManager/DAO.
        val root = File(context.filesDir, "recovery-upgrade-corpus")
        val marker = File(root, "expected.json")
        val roundTrip = File(root, "toolchain-roundtrip-326-v2/expected.json")
        check(marker.isFile && roundTrip.isFile) { "Required retained corpus is absent; never seed/reset it here" }
        val roundTripMetadata = JSONObject(roundTrip.readText())
        check(roundTripMetadata.getInt("writerUid") == Process.myUid())
        check(roundTripMetadata.getString("originalMarkerSha256") == digest(marker))
        // Use the existing independent row: the original Everything entry is grouped,
        // so its individual title is not guaranteed to be the group's displayed title.
        val expectedRow = JSONObject(roundTripMetadata.getString("newRawRow"))
        expectedRowId = roundTripMetadata.getLong("id")
        check(expectedRowId == expectedRow.getLong("id"))
        expectedTitle = expectedRow.getString("title")
        check(expectedTitle == "Toolchain round-trip 326")

        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        fixture = LegacyVaultCorpus()
        check(fixture.security.isMasterPasswordSet()) { "Refuse first-time setup" }
        val existingRow = checkNotNull(fixture.db.passwordEntryDao().getPasswordEntryById(expectedRowId))
        check(existingRow.title == expectedTitle)
        check(existingRow.passwordGroupId == null && existingRow.keepassDatabaseId == null &&
            existingRow.mdbxDatabaseId == null && existingRow.bitwardenVaultId == null)
        val currentRows = JSONObject(rawRows()).getJSONArray("password_entries")
        val currentRow = (0 until currentRows.length()).map { currentRows.getJSONObject(it) }
            .single { it.getLong("id") == expectedRowId }
        check(currentRow.toString() == expectedRow.toString()) { "Existing independent row changed" }
        markerDigest = digest(marker)
        originalRows = rawRows()
        originalAliases = aliases()

        settings = SettingsManager(context)
        @Suppress("UNCHECKED_CAST")
        val store = SettingsManager::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(settings) as DataStore<Preferences>
        settingsStore = store
        originalSettings = store.data.first()
        originalAutoLockMinutes = settings.settingsFlow.first().autoLockMinutes
        settingsTouched = true
        settings.updateDisablePasswordVerification(false)
        settings.updateBiometricEnabled(false)
        settings.updateAutoLockMinutes(5)
        settings.updateQuickSetupCompleted(true)
        withTimeout(5_000) {
            settings.settingsFlow.first {
                !it.disablePasswordVerification && !it.biometricEnabled &&
                    it.autoLockMinutes == 5 && it.quickSetupCompleted
            }
        }
        SessionManager.updateAutoLockTimeout(5)
        SessionManager.markLocked()
    }

    @After fun closeOnlyOwnedActivityAndRestoreAllAppSettings() = runBlocking {
        val failures = mutableListOf<Throwable>()
        fun preserveFailure(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(failures::add) }
        preserveFailure { scenario?.close() }
        scenario = null
        if (settingsTouched) {
            runCatching {
                val original = checkNotNull(originalSettings)
                settingsStore.updateData { original }
                check(settingsStore.data.first().asMap() == original.asMap()) { "App settings were not restored exactly" }
                SessionManager.updateAutoLockTimeout(originalAutoLockMinutes)
            }.exceptionOrNull()?.let(failures::add)
        }
        if (::fixture.isInitialized) {
            preserveFailure { SessionManager.markLocked() }
            preserveFailure {
                markerDigest?.let { check(it == digest(fixture.marker)) { "The original fixture marker changed" } }
                originalRows?.let { check(it == rawRows()) { "Existing database columns/ciphertexts changed" } }
                originalAliases?.let { check(it == aliases()) { "Keystore alias membership changed" } }
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    @Test fun wrongOriginalPasswordKeepsRetainedDataAndClosedDraftCannotAuthorize() {
        launchMain()
        waitForLogin()
        compose.onNode(hasSetTextAction()).performTextInput("Wrong synthetic toolchain password 326!")
        compose.onNodeWithText(label(R.string.unlock)).performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.error_invalid_password)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.error_invalid_password)).assertIsDisplayed()
        assertFalse(SessionManager.isUnlocked.value)
        assertOriginalTitleAbsentForStableFrames()
        assertEquals(originalRows, rawRows())
        assertEquals(markerDigest, digest(fixture.marker))
        assertEquals(originalAliases, aliases())
        capturePasswordEvidence("wrong-password")

        // Closing this owned Activity tests draft disposal, not a user-tapped Cancel control.
        currentScenario().close()
        scenario = null
        launchMain()
        waitForLogin()
        val reopenedDraft = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text
        assertEquals("The closed login draft must not be restored", "", reopenedDraft)
        assertFalse(SessionManager.isUnlocked.value)
        assertOriginalTitleAbsentForStableFrames()
        assertEquals(originalRows, rawRows())
        capturePasswordEvidence("closed-draft-reopened-locked")
        enterOriginalPassword()
        showOriginalRecord()
        assertTrue(SessionManager.isUnlocked.value)
        assertEquals(originalRows, rawRows())
        assertEquals(markerDigest, digest(fixture.marker))
        assertEquals(originalAliases, aliases())
        capturePasswordEvidence("original-password-restored-record")
    }

    private fun capturePasswordEvidence(step: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "toolchain-main-password-evidence").apply { check(isDirectory || mkdirs()) }
        val image = File(directory, "$step.png")
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val nonce = java.util.UUID.randomUUID().toString()
        val marker = File(directory, "$step.json")
        val ack = File(directory, "$step.ack")
        val data = JSONObject().put("step", step).put("nonce", nonce).put("uid", Process.myUid())
            .put("package", context.packageName).put("image", image.name).put("state", "ready")
        marker.writeText(data.toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_PASSWORD_CAPTURE ${marker.absolutePath}\n") })
        if (InstrumentationRegistry.getArguments().getString("toolchainMainPasswordPause") == "true") {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < deadline && (!ack.isFile || ack.readText().trim() != nonce)) SystemClock.sleep(100)
            val accepted = ack.isFile && ack.readText().trim() == nonce
            marker.writeText(data.put("state", if (accepted) "acknowledged" else "timed-out").toString(2))
            check(accepted) { "Password screenshot acknowledgement timed out at $step" }
        }
    }

    @Test fun sharedLockHidesAuthenticatedUiAndOriginalPasswordCanReopenIt() {
        launchMain()
        enterOriginalPassword()
        showOriginalRecord()
        val oldGeneration = SessionManager.lockGeneration.value
        currentScenario().onActivity { SessionManager.markLocked() }
        waitForLogin()
        assertTrue(SessionManager.lockGeneration.value > oldGeneration)
        assertFalse(SessionManager.isUnlocked.value)
        assertOriginalTitleAbsentForStableFrames()
        enterOriginalPassword()
        showOriginalRecord()
        assertTrue(SessionManager.isUnlocked.value)
    }

    @Test fun configurationRecreationKeepsTheAuthenticatedSession() {
        launchMain()
        enterOriginalPassword()
        showOriginalRecord()
        val generation = SessionManager.lockGeneration.value
        var taskId = -1
        currentScenario().onActivity { taskId = it.taskId }
        currentScenario().recreate()
        waitForAuthenticatedNavigation()
        currentScenario().onActivity { assertEquals(taskId, it.taskId) }
        assertEquals("Configuration changes must not revoke the session", generation, SessionManager.lockGeneration.value)
        assertTrue(SessionManager.isUnlocked.value)
        showOriginalRecord()
        compose.onAllNodesWithText(label(R.string.enter_master_password)).assertCountEquals(0)
    }

    @Test fun existingDeveloperBypassRemainsUsableWhileSharedSessionIsLocked() = runBlocking {
        // Never create/reconfigure a wrapper to satisfy this test. Require its existing mode.
        val wrapperBefore = fixture.security.getProtectedString("mdk_keystore_blob")
        check(wrapperBefore?.startsWith("CP|") == true) { "Existing compatible wrapper required; no key changes allowed" }
        settings.updateDisablePasswordVerification(true)
        withTimeout(5_000) { settings.settingsFlow.first { it.disablePasswordVerification } }
        launchMain()
        waitForAuthenticatedNavigation()
        assertTrue("Existing bypass startup must really unlock its compatible wrapper", SessionManager.isUnlocked.value)
        showOriginalRecord()
        var model: PasswordViewModel? = null
        currentScenario().onActivity {
            model = ViewModelProvider(it)[PasswordViewModel::class.java]
            assertTrue(checkNotNull(model).isAuthenticated.value)
            SessionManager.markLocked()
        }
        // This is the same UI/session state combination as a failed bypass unlock,
        // but deliberately does not claim that the Keystore provider itself failed.
        waitForAuthenticatedNavigation()
        assertFalse(SessionManager.isUnlocked.value)
        assertTrue(checkNotNull(model).isAuthenticated.value)
        assertTrue("The existing MDK wrapper must remain unchanged",
            wrapperBefore == fixture.security.getProtectedString("mdk_keystore_blob"))
        settings.updateDisablePasswordVerification(false)
        withTimeout(5_000) { settings.settingsFlow.first { !it.disablePasswordVerification } }
        waitForLogin()
        assertFalse(SessionManager.isUnlocked.value)
        assertOriginalTitleAbsentForStableFrames()
        enterOriginalPassword()
        showOriginalRecord()
    }

    private fun launchMain() {
        check(scenario == null)
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun currentScenario() = checkNotNull(scenario)

    private fun label(id: Int): String {
        var result: String? = null
        currentScenario().onActivity { result = it.getString(id) }
        return checkNotNull(result)
    }

    private fun waitForLogin() {
        val loginLabel = label(R.string.enter_master_password)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(loginLabel).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(loginLabel).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.setup_master_password)).assertCountEquals(0)
    }

    private fun enterOriginalPassword() {
        waitForLogin()
        compose.onNode(hasSetTextAction()).performTextInput(fixture.password)
        compose.onNodeWithText(label(R.string.unlock)).performClick()
        waitForAuthenticatedNavigation()
        assertTrue(SessionManager.isUnlocked.value)
    }

    private fun waitForAuthenticatedNavigation() {
        val navigationLabel = label(R.string.nav_passwords_short)
        val loginLabel = label(R.string.enter_master_password)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(navigationLabel).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(loginLabel).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText(navigationLabel).onFirst().assertIsDisplayed()
    }

    private fun showOriginalRecord() {
        compose.onAllNodesWithText(label(R.string.nav_passwords_short)).onFirst().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(expectedTitle).onFirst().assertIsDisplayed()
    }

    private fun assertOriginalTitleAbsentForStableFrames() {
        val deadline = SystemClock.uptimeMillis() + 1_500L
        while (SystemClock.uptimeMillis() < deadline) {
            compose.waitForIdle()
            compose.onAllNodesWithText(expectedTitle).assertCountEquals(0)
            compose.onNodeWithText(label(R.string.enter_master_password)).assertIsDisplayed()
            SystemClock.sleep(50)
        }
    }

    private fun rawRows(): String {
        val result = JSONObject()
        for (table in listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments", "local_mdbx_databases")) {
            val rows = JSONArray()
            fixture.db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    for (name in cursor.columnNames.sorted()) {
                        val index = cursor.getColumnIndexOrThrow(name)
                        val value: Any = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(index))
                            else -> cursor.getString(index)
                        }
                        row.put(name, value)
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        return result.toString()
    }

    private fun aliases(): Set<String> = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .aliases().toList().toSet()

    private fun digest(file: File): String {
        val hasher = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                hasher.update(bytes, 0, count)
            }
        }
        return hasher.digest().joinToString("") { "%02x".format(it) }
    }
}

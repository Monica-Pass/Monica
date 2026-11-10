package takagi.ru.monica.bitwarden

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real LoginScreen -> VM -> Repository -> owned localhost TLS; no production authentication seam. */
@RunWith(AndroidJUnit4::class)
class ToolchainBitwardenLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val runId = UUID.randomUUID().toString()
    private val pictures by lazy { File(context.filesDir, "toolchain-bw-login/$runId").apply { check(mkdirs()) } }

    @Before fun requireOwnedSyntheticUser() = runBlocking {
        check(Process.myUid() / 100_000 == 11)
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("toolchainBitwardenUi") == "yes")
        val expected = checkNotNull(args.getString("toolchainTargetApkSha256"))
        check(expected.matches(Regex("[0-9a-f]{64}")) && expected == sha(File(context.applicationInfo.sourceDir)))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun realLoginRejectsWrongPasswordCancelsDraftAndPublishesAnOfflineUnlockableAccount() = runBlocking<Unit> {
        val database = PasswordDatabase.getDatabase(context)
        val baseline = rows(database)
        val security = SecurityManager(context)
        val originalMirror = security.getProtectedString("bitwarden_settings_recovery_v1")
        val settings = RecoverableBitwardenSettings.open(context)
        val originalSettings = settings.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val devicePreferences = context.getSharedPreferences("bitwarden_device", android.content.Context.MODE_PRIVATE)
        val originalDevicePreferences = devicePreferences.all.toMap()
        val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val originalMdk = (mdkField.get(null) as? ByteArray)?.copyOf()
        var primaryFailure: Throwable? = null
        try {
            // Prevent a UI model from restoring/syncing unrelated pre-existing synthetic accounts.
            check(settings.edit().putBoolean("auto_sync_enabled", false).putBoolean("never_lock_bitwarden", false).commit())
            BitwardenAuthenticatedRepositoryInstrumentedTest().withFixture { fixture ->
                val models = ViewModelStore()
                lateinit var model: BitwardenViewModel
                var modelJob: Job? = null
                var visible by mutableStateOf(true)
                var completed by mutableStateOf(false)
                val successes = AtomicInteger()
                val cancellations = AtomicInteger()
                val draft = fixture.uiEnvironmentDraft()
                var uiFailure: Throwable? = null
                try {
                    compose.runOnUiThread {
                        model = BitwardenViewModel(compose.activity.application)
                        models.put("owned-login", model)
                        modelJob = model.viewModelScope.coroutineContext[Job]
                    }
                    compose.setContent { MonicaTheme {
                        if (visible) BitwardenLoginScreen(model,
                            onNavigateBack = { cancellations.incrementAndGet(); visible = false },
                            onLoginSuccess = { successes.incrementAndGet(); completed = true; visible = false },
                            initialEnvironmentDraft = draft, initialEmail = fixture.uiEmail)
                        else Text(if (completed) "Synthetic login callback completed" else "Synthetic login draft cancelled")
                    } }
                    waitLogin()
                    assertEquals(0, fixture.uiRequestCount)
                    compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Wrong synthetic login password 326!")
                    capture("wrong-password-before-submit")
                    compose.onNodeWithTag("bitwarden_login_submit").performScrollTo().performClick()
                    compose.waitUntil(60_000) { model.loginState.value is BitwardenViewModel.LoginState.Error }
                    val failureText = (model.loginState.value as BitwardenViewModel.LoginState.Error).message
                    compose.onNodeWithText(failureText).performScrollTo().assertIsDisplayed()
                    assertEquals(0, successes.get())
                    assertEquals(baseline, rows(database))
                    assertEquals(2, fixture.uiRequestCount)
                    assertEquals(listOf("/identity/accounts/prelogin", "/identity/connect/token"),
                        List(2) { fixture.uiTakeRequest().requestUrl!!.encodedPath })
                    capture("wrong-password-rejected-no-account")

                    // This is the production toolbar Back control, not a fake cancellation callback.
                    compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextReplacement("Unsubmitted synthetic secret")
                    capture("unsubmitted-draft-before-back")
                    compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
                    compose.waitUntil(10_000) { !visible }
                    assertEquals(1, cancellations.get())
                    assertEquals(0, successes.get())
                    assertEquals(2, fixture.uiRequestCount)
                    assertEquals(baseline, rows(database))
                    compose.runOnIdle { visible = true }
                    waitLogin()
                    assertEquals("", compose.onNodeWithTag("bitwarden_login_password").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.EditableText)?.text)
                    assertEquals(2, fixture.uiRequestCount)
                    capture("cancelled-draft-reopened-empty")

                    compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput(fixture.uiPassword)
                    capture("correct-password-before-submit")
                    compose.onNodeWithTag("bitwarden_login_submit").performScrollTo().performClick()
                    compose.waitUntil(60_000) { successes.get() == 1 }
                    val account = fixture.row()
                    assertEquals(fixture.uiEmail, account.email)
                    assertNotNull(account.encryptedMasterKey)
                    assertNotNull(account.encryptedEncKey)
                    assertNotNull(account.encryptedMacKey)
                    assertNotNull(account.encryptedAccessToken)
                    assertNotNull(account.encryptedRefreshToken)
                    fixture.assertUserKey(account.id)
                    assertEquals("Only the owned account may be added", baseline, rows(database, account.id))
                    assertEquals(4, fixture.uiRequestCount)
                    assertEquals(listOf("/identity/accounts/prelogin", "/identity/connect/token"),
                        List(2) { fixture.uiTakeRequest().requestUrl!!.encodedPath })
                    // Completion page belongs to the test host; actual success is proved by the above HTTP + Room + key checks.
                    compose.onNodeWithText("Synthetic login callback completed").assertIsDisplayed()
                    capture("real-login-callback-and-persisted-account")

                    // New-account login itself does not request a profile. Verify the published account client separately.
                    val accountClient = fixture.attachmentContext(account)
                    try {
                        accountClient.requireCurrent()
                        val profileResponse = accountClient.vaultApi.profile("Bearer ${accountClient.accessToken}")
                        try {
                            assertTrue(profileResponse.isSuccessful)
                            val profile = requireNotNull(profileResponse.body())
                            assertEquals(fixture.uiEmail, profile.email)
                            assertFalse(profile.id.isNullOrBlank())
                        } finally { profileResponse.errorBody()?.close() }
                        accountClient.requireCurrent()
                        assertEquals(5, fixture.uiRequestCount)
                        val profileRequest = fixture.uiTakeRequest()
                        assertEquals("/api/accounts/profile", profileRequest.requestUrl!!.encodedPath)
                        assertEquals("Bearer ${accountClient.accessToken}", profileRequest.getHeader("Authorization"))
                        assertEquals(account, fixture.row())
                    } finally { accountClient.wrappingKey.clear() }

                    val requestCount = fixture.uiRequestCount
                    fixture.repository.forceLock(account.id)
                    assertFalse(fixture.repository.isVaultUnlocked(account.id))
                    val lockedAccount = fixture.row()
                    assertTrue(fixture.repository.unlock(account.id, "Wrong synthetic offline password") is BitwardenRepository.UnlockResult.Error)
                    assertEquals(lockedAccount, fixture.row())
                    assertFalse(fixture.repository.isVaultUnlocked(account.id))
                    assertEquals(requestCount, fixture.uiRequestCount)
                    assertEquals(BitwardenRepository.UnlockResult.Success, fixture.repository.unlock(account.id, fixture.uiPassword))
                    assertTrue(fixture.repository.isVaultUnlocked(account.id))
                    fixture.assertUserKey(account.id)
                    assertEquals(requestCount, fixture.uiRequestCount)
                    assertEquals(baseline, rows(database, account.id))
                } catch (error: Throwable) {
                    uiFailure = error
                    throw error
                } finally {
                    var uiCleanupFailure: Throwable? = null
                    suspend fun cleanupUi(block: suspend () -> Unit) {
                        try { block() } catch (error: Throwable) {
                            if (uiCleanupFailure == null) uiCleanupFailure = error else uiCleanupFailure!!.addSuppressed(error)
                        }
                    }
                    cleanupUi { compose.runOnUiThread { visible = false } }
                    cleanupUi { compose.waitForIdle() }
                    cleanupUi { compose.runOnUiThread { models.clear() } }
                    cleanupUi { modelJob?.cancelAndJoin() }
                    uiCleanupFailure?.let { failure -> if (uiFailure != null) uiFailure!!.addSuppressed(failure) else throw failure }
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { restoreSettings(settings, originalSettings) }
            cleanup { restoreSettings(devicePreferences, originalDevicePreferences) }
            cleanup {
                if (originalMirror == null) security.removeProtectedString("bitwarden_settings_recovery_v1")
                else security.putProtectedString("bitwarden_settings_recovery_v1", originalMirror)
                assertEquals(originalMirror, security.getProtectedString("bitwarden_settings_recovery_v1"))
            }
            cleanup { assertArrayEquals(originalMdk, mdkField.get(null) as? ByteArray) }
            cleanup { assertEquals(baseline, rows(database)) }
            originalMdk?.fill(0)
            cleanupFailure?.let { failure -> if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure }
        }
    }

    private fun waitLogin() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("bitwarden_login_password").fetchSemanticsNodes().isNotEmpty()
    }

    private fun restoreSettings(settings: SharedPreferences, original: Map<String, Any?>) {
        val edit = settings.edit()
        (settings.all.keys - original.keys).forEach(edit::remove)
        original.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            null -> edit.remove(key)
            else -> error("Unknown settings type")
        } }
        check(edit.commit())
        assertEquals(original, settings.all)
    }

    private fun rows(database: PasswordDatabase, excludeOwnedVault: Long? = null): String {
        val result = JSONObject()
        for (table in listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments",
            "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults")) {
            val values = JSONArray()
            database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    if (table == "bitwarden_vaults" && excludeOwnedVault != null && cursor.getLong(cursor.getColumnIndexOrThrow("id")) == excludeOwnedVault) continue
                    val row = JSONObject()
                    cursor.columnNames.sorted().forEach { name ->
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
                    values.put(row)
                }
            }
            result.put(table, values)
        }
        return result.toString()
    }

    private fun capture(step: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val image = File(pictures, "$step.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val nonce = UUID.randomUUID().toString()
        val marker = File(pictures, "$step.json")
        val ack = File(pictures, "$step.ack")
        val data = JSONObject().put("step", step).put("nonce", nonce).put("uid", Process.myUid())
            .put("package", context.packageName).put("image", image.name).put("state", "ready")
        marker.writeText(data.toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_BW_UI_CAPTURE ${marker.absolutePath}\n") })
        if (args.getString("toolchainBitwardenUiPause") == "true") {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < deadline && (!ack.isFile || ack.readText().trim() != nonce)) SystemClock.sleep(100)
            val accepted = ack.isFile && ack.readText().trim() == nonce
            marker.writeText(data.put("state", if (accepted) "acknowledged" else "timed-out").toString(2))
            check(accepted) { "Bitwarden screenshot acknowledgement timed out at $step" }
        }
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

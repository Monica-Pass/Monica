package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.security.SessionManager
import androidx.lifecycle.Lifecycle
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.ui.BitwardenAccountScreen
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Production native UI and real local HTTP; creates/deletes only this test's UUID fixture. */
@RunWith(AndroidJUnit4::class)
class BitwardenAccountUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val localized get() = LocaleHelper.setLocale(context, Language.ENGLISH)
    private val models = ViewModelStore()
    private val json = Json { ignoreUnknownKeys = true }

    private var originalAppUnlocked = false
    @Before fun authorizeSyntheticSession() {
        originalAppUnlocked = SessionManager.isUnlocked.value
        compose.runOnUiThread { SessionManager.markUnlocked() }
    }
    @After fun clearModels() { compose.runOnUiThread {
        models.clear()
        if (originalAppUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
    } }

    @Test fun nativeRegistrationUsesVerifiedEmailAndDoesNotCreateAPartialLocalVault() {
        MockWebServer().use { server ->
            server.enqueue(ok("{\"emailVerificationToken\":null}"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val email = "registration-${UUID.randomUUID()}@example.invalid"
            val database = PasswordDatabase.getDatabase(context)
            val originalIds = runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() }
            showLogin(server, email)
            compose.onNodeWithTag("bitwarden_create_account").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("bitwarden_registration_dialog").assertIsDisplayed()
            compose.onNodeWithTag("bitwarden_registration_email").assertTextContains(email)
            compose.onNodeWithTag("bitwarden_registration_password").assertDoesNotExist()
            compose.onNodeWithTag("bitwarden_registration_submit").assertIsDisplayed().performClick()
            waitForTag("bitwarden_registration_token")
            val start = request(server)
            assertEquals("/identity/accounts/register/send-verification-email", start.path)
            assertEquals(email, json.parseToJsonElement(start.body.readUtf8()).jsonObject["email"]!!.jsonPrimitive.content)
            assertNull(start.getHeader("Authorization"))
            compose.onNodeWithTag("bitwarden_registration_token").performTextInput("synthetic-email-token")
            compose.onNodeWithTag("bitwarden_registration_submit").performClick()
            waitForTag("bitwarden_registration_password")
            val verification = request(server)
            assertEquals("/identity/accounts/register/verification-email-clicked", verification.path)
            assertEquals("synthetic-email-token", json.parseToJsonElement(verification.body.readUtf8()).jsonObject["emailVerificationToken"]!!.jsonPrimitive.content)
            val password = "Synthetic native registration 42! unique"
            compose.onNodeWithTag("bitwarden_registration_password").performScrollTo().assertIsDisplayed().performTextInput(password)
            compose.onNodeWithTag("bitwarden_registration_confirmation").performScrollTo().assertIsDisplayed().performTextInput(password)
            assertNotNull(compose.onNodeWithTag("bitwarden_registration_password").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Password))
            screenshotIfRequested("registration-password")
            compose.onNodeWithTag("bitwarden_registration_submit").performClick()
            waitForText(localized.getString(R.string.bitwarden_account_registration_success), 60_000)
            val finish = request(server)
            assertEquals("/identity/accounts/register/finish", finish.path)
            val raw = finish.body.readUtf8()
            assertFalse(raw.contains(password))
            assertTrue(json.parseToJsonElement(raw).jsonObject.containsKey("MasterPasswordUnlock"))
            compose.onNodeWithTag("bitwarden_registration_password").assertDoesNotExist()
            assertEquals(originalIds, runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() })
            screenshotIfRequested("registration-complete")
            compose.onNodeWithTag("bitwarden_registration_submit").performClick()
            compose.onNodeWithTag("bitwarden_registration_dialog").assertDoesNotExist()
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun nativePasswordHintRequestSendsOnlyEmailAndKeepsLoginAvailable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val email = "hint-${UUID.randomUUID()}@example.invalid"
            showLogin(server, email)
            compose.onNodeWithTag("bitwarden_request_password_hint").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("bitwarden_password_hint_dialog").assertIsDisplayed()
            compose.onNodeWithTag("bitwarden_password_hint_email").assertTextContains(email)
            compose.onNodeWithTag("bitwarden_password_hint_submit").assertIsDisplayed().performClick()
            waitForText(localized.getString(R.string.bitwarden_account_hint_sent))
            val sent = request(server)
            assertEquals("/api/accounts/password-hint", sent.path)
            val body = json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(setOf("email"), body.keys)
            assertEquals(email, body["email"]!!.jsonPrimitive.content)
            assertNull(sent.getHeader("Authorization"))
            screenshotIfRequested("password-hint-sent")
            compose.onNodeWithTag("bitwarden_password_hint_submit").performClick()
            compose.onNodeWithTag("bitwarden_password_hint_dialog").assertDoesNotExist()
            compose.onNodeWithTag("bitwarden_create_account").assertExists()
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun nativeAccountDeviceListValidatesProfileAndRevokesChangedStampBeforeLoadingDevices() {
        MockWebServer().use { server ->
            val fixture = DeviceFixture(server)
            try {
                runBlocking { fixture.initialize() }
                val model = model()
                show { BitwardenAccountScreen(model, fixture.vaultId) {} }
                waitForTag("bitwarden_account_device_owned-device")
                compose.onNodeWithTag("bitwarden_account_device_owned-device").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Synthetic Android").assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.bitwarden_account_device_trusted), substring = true).assertIsDisplayed()
                compose.onNodeWithText("never expose encrypted keys").assertDoesNotExist()
                assertEquals(listOf("/api/accounts/profile", "/api/devices"), fixture.paths.toList())
                screenshotIfRequested("account-devices")
                fixture.stamp.set("synthetic-stamp-revoked")
                compose.onNodeWithContentDescription(localized.getString(R.string.refresh)).performClick()
                compose.waitUntil(20_000) {
                    !fixture.repository.isVaultUnlocked(fixture.vaultId) &&
                        compose.onAllNodesWithTag("bitwarden_account_device_owned-device").fetchSemanticsNodes().isEmpty()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("bitwarden_account_device_owned-device").assertDoesNotExist()
                assertEquals(listOf("/api/accounts/profile", "/api/devices", "/api/accounts/profile"), fixture.paths.toList())
                // Runtime revocation hides the UI first; wait for its durable half, not UI idleness.
                val preserved = runBlocking { withTimeout(20_000) {
                    fixture.database.bitwardenVaultDao().getVaultByIdFlow(fixture.vaultId).first {
                        it?.isLocked == true && !it.isConnected && it.encryptedAccessToken == null &&
                            it.encryptedRefreshToken == null && it.accessTokenExpiresAt == null
                    }
                } }
                assertNotNull(preserved)
                assertTrue(preserved!!.isLocked)
                assertFalse(preserved.isConnected)
                assertNull(preserved.encryptedAccessToken)
                assertNull(preserved.encryptedRefreshToken)
                assertNull(preserved.accessTokenExpiresAt)
                screenshotIfRequested("account-reauthentication")
            } finally {
                compose.runOnIdle { models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    @Test fun nativeDeviceListClearsOnLockAndReloadsAfterReturningToForeground() {
        MockWebServer().use { server ->
            val fixture = DeviceFixture(server)
            try {
                runBlocking { fixture.initialize() }
                val viewModel = model()
                show { BitwardenAccountScreen(viewModel, fixture.vaultId) {} }
                waitForTag("bitwarden_account_device_owned-device")
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(20_000) { fixture.paths.count { it == "/api/devices" } == 2 }
                waitForTag("bitwarden_account_device_owned-device")
                runBlocking { fixture.repository.forceLock(fixture.vaultId) }
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithTag("bitwarden_account_device_owned-device").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText("Synthetic Android").assertDoesNotExist()
            } finally {
                compose.runOnIdle { models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    @Test fun lateAccountDeviceResponseCannotReappearAfterLock() {
        MockWebServer().use { server ->
            val fixture = DeviceFixture(server)
            try {
                runBlocking { fixture.initialize() }
                fixture.deviceResponseGate = CountDownLatch(1)
                val viewModel = model()
                show { BitwardenAccountScreen(viewModel, fixture.vaultId) {} }
                assertTrue(fixture.deviceResponseEntered.await(20, TimeUnit.SECONDS))
                runBlocking { fixture.repository.forceLock(fixture.vaultId) }
                fixture.deviceResponseGate!!.countDown()
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(localized.getString(R.string.legacy_ui_vault_locked)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                compose.onNodeWithText("Synthetic Android").assertDoesNotExist()
                assertFalse(fixture.repository.isVaultUnlocked(fixture.vaultId))
            } finally {
                fixture.deviceResponseGate?.countDown()
                compose.runOnIdle { models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    @Test fun existingServerConfigurationLocksBeforeReauthenticationAndCancelPreservesSavedEndpoints() {
        MockWebServer().use { server ->
            val fixture = DeviceFixture(server)
            try {
                runBlocking { fixture.initialize() }
                val original = requireNotNull(runBlocking { fixture.database.bitwardenVaultDao().getVaultById(fixture.vaultId) })
                val viewModel = model()
                show { BitwardenAccountScreen(viewModel, fixture.vaultId) {} }
                waitForTag("bitwarden_account_device_owned-device")
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("bitwarden_account_server_configuration"))
                compose.onNodeWithTag("bitwarden_account_server_configuration").performClick()
                waitForTag("bitwarden_server_configuration")
                assertFalse("Editing must revoke the previous unlocked account", fixture.repository.isVaultUnlocked(fixture.vaultId))
                compose.onNodeWithTag("bitwarden_server_base").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithText(localized.getString(R.string.bitwarden_server_endpoints)).performScrollTo().performClick()
                compose.onNodeWithTag("bitwarden_server_api").performScrollTo().performTextReplacement(server.url("/Candidate/").toString())
                compose.onNodeWithTag("bitwarden_server_apply").performClick()
                compose.onNodeWithTag("bitwarden_server_apply").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("bitwarden_login_email").performScrollTo().assertIsNotEnabled().assertTextContains(original.email)
                compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("synthetic unsubmitted password")
                compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
                waitForTag("bitwarden_account_screen")
                val unchanged = requireNotNull(runBlocking { fixture.database.bitwardenVaultDao().getVaultById(fixture.vaultId) })
                assertTrue(unchanged.isLocked)
                assertEquals(original.serverUrl, unchanged.serverUrl)
                assertEquals(original.apiUrl, unchanged.apiUrl)
                assertEquals(original.identityUrl, unchanged.identityUrl)
                assertEquals(original.notificationsUrl, unchanged.notificationsUrl)
                assertEquals(original.webVaultUrl, unchanged.webVaultUrl)
                assertEquals(original.environmentPreset, unchanged.environmentPreset)
                assertEquals(original.transportRevision, unchanged.transportRevision)
                assertEquals(original.transportSecretRef, unchanged.transportSecretRef)
                assertEquals("No login is submitted merely by editing or cancelling", listOf("/api/accounts/profile", "/api/devices"), fixture.paths.toList())
            } finally {
                compose.runOnIdle { models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    @Test fun capturedExistingAccountSubmitCannotStartNetworkAfterASecondVaultLock() {
        MockWebServer().use { server ->
            val fixture = DeviceFixture(server)
            try {
                runBlocking { fixture.initialize() }
                val viewModel = model()
                show { BitwardenAccountScreen(viewModel, fixture.vaultId) {} }
                waitForTag("bitwarden_account_device_owned-device")
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("bitwarden_account_server_configuration"))
                compose.onNodeWithTag("bitwarden_account_server_configuration").performClick()
                waitForTag("bitwarden_server_configuration")
                compose.onNodeWithTag("bitwarden_server_apply").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Synthetic stale login password")
                val capturedSubmit = requireNotNull(compose.onNodeWithTag("bitwarden_login_submit").performScrollTo()
                    .assertIsEnabled().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                val requestCount = server.requestCount
                compose.runOnUiThread {
                    // One main-thread turn prevents Compose's asynchronous effect from hiding
                    // the missing dispatch guard. The production callback itself must reject it.
                    runBlocking { fixture.repository.forceLock(fixture.vaultId) }
                    capturedSubmit()
                }
                waitForTag("bitwarden_account_screen")
                assertEquals("A revoked edit callback must not issue prelogin or token HTTP", requestCount, server.requestCount)
                assertTrue(viewModel.loginState.value is BitwardenViewModel.LoginState.Idle)
                assertFalse(fixture.repository.isVaultUnlocked(fixture.vaultId))
            } finally {
                compose.runOnIdle { models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    private fun model(): BitwardenViewModel = BitwardenViewModel(compose.activity.application).also {
        models.put("auth-native-${UUID.randomUUID()}", it)
    }

    private fun showLogin(server: MockWebServer, email: String) {
        val model = model()
        show { BitwardenLoginScreen(model, {}, {}) }
        compose.onNodeWithTag("bitwarden_server_configure").performScrollTo().performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_server_self_hosted)).performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_server_base").performScrollTo().performTextReplacement(server.url("/").toString())
        compose.onNodeWithTag("bitwarden_server_apply").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction() and hasText(localized.getString(R.string.legacy_ui_email_address)))
            .performScrollTo().performTextInput(email)
    }

    private fun show(content: @Composable () -> Unit) {
        val english = localized
        val configuration = Configuration(english.resources.configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeout: Long = 20_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun request(server: MockWebServer): RecordedRequest =
        requireNotNull(server.takeRequest(10, TimeUnit.SECONDS)) { "Expected synthetic account request was not sent" }

    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("authAccountScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "auth-account-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private inner class DeviceFixture(val server: MockWebServer) {
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val paths = CopyOnWriteArrayList<String>()
        val stamp = AtomicReference("synthetic-stamp-original")
        private val key = SymmetricCryptoKey(ByteArray(32) { 7 }, ByteArray(32) { 11 })
        private val uuid = UUID.randomUUID().toString()
        private val email = "native-account-$uuid@example.invalid"
        private val originallyUnlocked = SessionManager.isUnlocked.value
        val deviceResponseEntered = CountDownLatch(1)
        @Volatile var deviceResponseGate: CountDownLatch? = null
        var vaultId = 0L

        suspend fun initialize() {
            SessionManager.markUnlocked()
            val endpoint = server.url("/").toString().trimEnd('/')
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = email, canonicalEmail = email, userId = uuid, accountKey = "native-account-$uuid",
                serverUrl = endpoint, identityUrl = "$endpoint/identity", apiUrl = "$endpoint/api/",
                // Synthetic opaque persisted markers prove invalidation actually clears storage.
                // HTTP uses the separate in-memory token and does not decrypt these fixture values.
                encryptedAccessToken = "synthetic-encrypted-access-marker",
                encryptedRefreshToken = "synthetic-encrypted-refresh-marker",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                revisionDate = stamp.get(), isLocked = false, isConnected = true, syncEnabled = false,
            ))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[vaultId] = key
            cache<String>("accessTokenCache")[vaultId] = "synthetic-native-device-token"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += path
                    if (request.getHeader("Authorization") != "Bearer synthetic-native-device-token") {
                        return MockResponse().setResponseCode(401)
                    }
                    return when (path) {
                        "/api/accounts/profile" -> ok("{\"id\":\"$uuid\",\"email\":\"$email\",\"securityStamp\":\"${stamp.get()}\"}")
                        "/api/devices" -> {
                            deviceResponseEntered.countDown()
                            deviceResponseGate?.await(20, TimeUnit.SECONDS)
                            ok("{\"data\":[{\"id\":\"owned-device\",\"name\":\"Synthetic Android\",\"isTrusted\":true,\"encryptedUserKey\":\"never expose encrypted keys\"}]}")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            BitwardenRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun close() {
            if (vaultId != 0L) {
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vaultId)?.clear()
                cache<String>("accessTokenCache").remove(vaultId)
                val row = database.bitwardenVaultDao().getVaultById(vaultId)
                if (row != null) {
                    check(row.accountKey == "native-account-$uuid" && row.email == email)
                    database.bitwardenVaultDao().deleteById(vaultId)
                }
            }
            key.clear()
            if (!originallyUnlocked) SessionManager.markLocked()
        }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

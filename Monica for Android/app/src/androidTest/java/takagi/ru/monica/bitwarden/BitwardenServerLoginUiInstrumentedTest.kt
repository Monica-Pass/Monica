package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.LoginResult
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.ui.BitwardenPasswordHintDialog
import takagi.ru.monica.bitwarden.ui.BitwardenRegistrationDialog
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Real form -> ViewModel -> repository/account service -> separate local native service paths. */
@RunWith(AndroidJUnit4::class)
class BitwardenServerLoginUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val english = LocaleHelper.setLocale(context, Language.ENGLISH)
    private val models = ViewModelStore()
    private val visible = mutableStateOf(true)
    private var originalUnlocked = false
    private var originalConfiguration: Configuration? = null

    @Before fun authorizeSyntheticSession() {
        originalUnlocked = SessionManager.isUnlocked.value
        compose.runOnUiThread { SessionManager.markUnlocked() }
    }

    @After fun clearOnlyOwnedUi() {
        compose.runOnUiThread { visible.value = false; models.clear() }
        originalConfiguration?.let { original -> compose.runOnUiThread {
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(original, compose.activity.resources.displayMetrics)
        } }
        compose.runOnUiThread {
            if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun primaryAndMfaRequestsUseTheCapturedExplicitIdentityAndProxyHeader() {
        MockWebServer().use { base -> MockWebServer().use { api -> MockWebServer().use { identity ->
            identity.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/IndependentIdentity/accounts/prelogin" -> json("{\"kdf\":0,\"kdfIterations\":600000}")
                    "/IndependentIdentity/connect/token" -> {
                        if (request.body.clone().readUtf8().contains("twoFactorToken=123456")) {
                            json("{\"error\":\"invalid_grant\",\"error_description\":\"Synthetic rejected code\"}", 400)
                        } else json("{\"error\":\"invalid_grant\",\"TwoFactorProviders\":[\"0\"],\"TwoFactorProviders2\":{\"0\":{}}}", 400)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val database = PasswordDatabase.getDatabase(context)
            val originalIds = runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() }
            val model = model()
            val email = "server-login-${UUID.randomUUID()}@example.com"
            val draft = draft(base, api, identity)
            show { BitwardenLoginScreen(model, { visible.value = false }, {}, initialEnvironmentDraft = draft, initialEmail = email) }
            compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Synthetic login password 42!")
            compose.onNodeWithTag("bitwarden_login_submit").performScrollTo().performClick()
            waitTag("bitwarden_two_factor_code", 60_000)
            compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("12")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag("bitwarden_two_factor_code")
            assertEquals("", compose.onNodeWithTag("bitwarden_two_factor_code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("Returning from an OTP app must not send a request automatically", 2, identity.requestCount)
            compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("bitwarden_two_factor_submit").performClick()
            compose.waitUntil(30_000) { identity.requestCount == 3 && model.loginState.value is BitwardenViewModel.LoginState.Error }
            val requests = List(3) { requireNotNull(identity.takeRequest(1, TimeUnit.SECONDS)) }
            assertEquals(listOf("/IndependentIdentity/accounts/prelogin", "/IndependentIdentity/connect/token", "/IndependentIdentity/connect/token"), requests.map { it.path })
            requests.forEach { assertEquals(PROXY_VALUE, it.getHeader(PROXY_NAME)) }
            assertTrue(requests.last().body.readUtf8().contains("twoFactorToken=123456"))
            assertEquals(0, api.requestCount)
            assertEquals(0, base.requestCount)
            assertEquals(originalIds, runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() })
            compose.runOnUiThread { SessionManager.markLocked() }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_two_factor_dialog").fetchSemanticsNodes().isEmpty() }
            assertTrue(model.loginState.value is BitwardenViewModel.LoginState.Idle)
            compose.runOnUiThread { SessionManager.markUnlocked() }
            compose.onNodeWithTag("bitwarden_two_factor_dialog").assertDoesNotExist()
            assertEquals(3, identity.requestCount)
        } } }
    }

    @Test fun backgroundDuringActiveMfaClearsChallengeKeysAndRejectsLateTokenResult() {
        MockWebServer().use { base -> MockWebServer().use { api -> MockWebServer().use { identity ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = AtomicBoolean(false)
            val loggedIn = AtomicBoolean(false)
            val tokenBody = AtomicReference<String>()
            val email = "active-mfa-${UUID.randomUUID()}@example.com"
            val database = PasswordDatabase.getDatabase(context)
            val originalIds = runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() }
            val draft = draft(base, api, identity)
            val model = model()
            identity.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/IndependentIdentity/accounts/prelogin" -> json("{\"kdf\":0,\"kdfIterations\":600000}")
                    "/IndependentIdentity/connect/token" -> {
                        if (request.body.clone().readUtf8().contains("twoFactorToken=123456")) {
                            entered.countDown()
                            if (!release.await(30, TimeUnit.SECONDS)) MockResponse().setResponseCode(504)
                            else {
                                returned.set(true)
                                json(requireNotNull(tokenBody.get()))
                            }
                        } else json("{\"error\":\"invalid_grant\",\"TwoFactorProviders\":[\"0\"],\"TwoFactorProviders2\":{\"0\":{}}}", 400)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            // A retired authentication must not progress to profile validation at all.
            api.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            try {
                show { BitwardenLoginScreen(model, { visible.value = false }, { loggedIn.set(true) },
                    initialEnvironmentDraft = draft, initialEmail = email) }
                compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Synthetic active MFA password 42!")
                compose.onNodeWithTag("bitwarden_login_submit").performScrollTo().performClick()
                waitTag("bitwarden_two_factor_code", 60_000)
                lateinit var challenge: LoginResult.TwoFactorRequired
                compose.runOnUiThread {
                    challenge = BitwardenViewModel::class.java.getDeclaredField("twoFactorState").apply { isAccessible = true }
                        .get(model) as LoginResult.TwoFactorRequired
                }
                assertTrue(challenge.tempMasterKey.any { it != 0.toByte() })
                assertTrue(challenge.tempStretchedKey.encKey.any { it != 0.toByte() })
                assertTrue(challenge.tempStretchedKey.macKey.any { it != 0.toByte() })
                val accountKey = ByteArray(64) { (it + 1).toByte() }
                val encryptedKey = try { BitwardenCrypto.encrypt(accountKey, challenge.tempStretchedKey) }
                    finally { accountKey.fill(0) }
                tokenBody.set("""{"access_token":"synthetic-late-access","refresh_token":"synthetic-late-refresh","expires_in":3600,"Key":"$encryptedKey"}""")
                compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("123456")
                compose.onNodeWithTag("bitwarden_two_factor_submit").performClick()
                assertTrue("MFA request must be in flight before backgrounding", entered.await(10, TimeUnit.SECONDS))
                lateinit var activeJob: Job
                compose.runOnUiThread {
                    assertEquals(BitwardenViewModel.LoginState.Loading, model.loginState.value)
                    activeJob = BitwardenViewModel::class.java.getDeclaredField("authenticationJob").apply { isAccessible = true }
                        .get(model) as Job
                    assertTrue(activeJob.isActive)
                }
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync {
                    assertEquals(BitwardenViewModel.LoginState.Idle, model.loginState.value)
                    listOf("twoFactorState", "pendingEnvironment", "pendingReauthentication").forEach { name ->
                        assertNull(name, BitwardenViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model))
                    }
                    assertTrue(challenge.tempMasterKey.all { it == 0.toByte() })
                    assertTrue(challenge.tempStretchedKey.encKey.all { it == 0.toByte() })
                    assertTrue(challenge.tempStretchedKey.macKey.all { it == 0.toByte() })
                }
                release.countDown()
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { returned.get() }
                runBlocking { withTimeout(10_000) { activeJob.join() } }
                compose.waitForIdle()
                assertEquals(BitwardenViewModel.LoginState.Idle, model.loginState.value)
                assertFalse(loggedIn.get())
                compose.onNodeWithTag("bitwarden_two_factor_dialog").assertDoesNotExist()
                assertEquals("", compose.onNodeWithTag("bitwarden_login_password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                assertEquals("Returning to the page must not retry the retired MFA", 3, identity.requestCount)
                assertEquals(0, api.requestCount)
                assertEquals(0, base.requestCount)
                assertEquals(originalIds, runBlocking { database.bitwardenVaultDao().getAllVaults().map { it.id }.toSet() })
            } finally {
                release.countDown()
                compose.runOnUiThread { model.resetLoginState(cancelInFlight = true) }
                runBlocking {
                    database.bitwardenVaultDao().getAllVaults().filter { it.email == email && it.serverUrl.trimEnd('/') == draft.baseUrl.trimEnd('/') }
                        .forEach { check(BitwardenRepository.getInstance(context).logout(it.id)) }
                }
            }
        } } }
    }

    @Test fun registrationTicketRetainsExplicitIdentityAndHeaderThroughEmailVerification() {
        MockWebServer().use { base -> MockWebServer().use { api -> MockWebServer().use { identity ->
            identity.enqueue(json("{\"emailVerificationToken\":null}"))
            identity.enqueue(MockResponse().setResponseCode(204))
            val model = model()
            val email = "server-registration-${UUID.randomUUID()}@example.com"
            val draft = draft(base, api, identity)
            show { BitwardenRegistrationDialog(model, draft, email, { visible.value = false }, { visible.value = false }) }
            compose.onNodeWithTag("bitwarden_registration_submit").performClick()
            waitTag("bitwarden_registration_token")
            compose.onNodeWithTag("bitwarden_registration_token").performScrollTo().performTextInput("unsubmitted-partial-token")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag("bitwarden_registration_token")
            assertEquals("", compose.onNodeWithTag("bitwarden_registration_token").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("Returning from email must keep the ticket without resending", 1, identity.requestCount)
            compose.onNodeWithTag("bitwarden_registration_token").performScrollTo().performTextInput("synthetic-email-token")
            compose.onNodeWithTag("bitwarden_registration_submit").performClick()
            waitTag("bitwarden_registration_password")
            val requests = List(2) { requireNotNull(identity.takeRequest(1, TimeUnit.SECONDS)) }
            assertEquals(listOf("/IndependentIdentity/accounts/register/send-verification-email", "/IndependentIdentity/accounts/register/verification-email-clicked"), requests.map { it.path })
            requests.forEach { assertEquals(PROXY_VALUE, it.getHeader(PROXY_NAME)) }
            assertTrue(requests.last().body.readUtf8().contains("synthetic-email-token"))
            assertEquals(0, base.requestCount)
            assertEquals(0, api.requestCount)
            compose.runOnUiThread { SessionManager.markLocked() }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_registration_dialog").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("bitwarden_registration_password").assertDoesNotExist()
            compose.runOnUiThread { SessionManager.markUnlocked() }
            compose.onNodeWithTag("bitwarden_registration_dialog").assertDoesNotExist()
            assertEquals("Unlocking does not resurrect or resubmit the retired ticket", 2, identity.requestCount)
        } } }
    }

    @Test fun passwordHintUsesExplicitApiAndCannotPublishCancelledBackgroundResult() {
        MockWebServer().use { base -> MockWebServer().use { api -> MockWebServer().use { identity ->
            val release = CountDownLatch(1)
            val returned = AtomicBoolean(false)
            api.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    release.await(20, TimeUnit.SECONDS)
                    returned.set(true)
                    return MockResponse().setResponseCode(204)
                }
            }
            try {
                val model = model()
                val draft = draft(base, api, identity)
                show { BitwardenPasswordHintDialog(model, draft, "server-hint@example.com", { visible.value = false }) }
                compose.onNodeWithTag("bitwarden_password_hint_submit").performClick()
                compose.waitUntil(20_000) { api.requestCount == 1 }
                val request = requireNotNull(api.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/IndependentApi/accounts/password-hint", request.path)
                assertEquals(PROXY_VALUE, request.getHeader(PROXY_NAME))
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                release.countDown()
                compose.waitUntil(10_000) { returned.get() }
                compose.waitForIdle()
                compose.onNodeWithTag("bitwarden_password_hint_dialog").assertIsDisplayed()
                compose.onNodeWithTag("bitwarden_password_hint_submit").assertIsEnabled()
                compose.onNodeWithText(english.getString(R.string.bitwarden_account_hint_sent)).assertDoesNotExist()
                assertEquals(0, base.requestCount)
                assertEquals(0, identity.requestCount)
            } finally { release.countDown() }
        } } }
    }

    @Test fun emailEditAndBackgroundClearPasswordButKeepCapturedConnectionUntilGlobalLock() {
        val model = model()
        val draft = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, "https://synthetic.example.com/",
            headers = listOf(BitwardenHeaderDraft(PROXY_NAME, PROXY_VALUE)))
        show { BitwardenLoginScreen(model, { visible.value = false }, {}, initialEnvironmentDraft = draft, initialEmail = "first@example.com") }
        compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Synthetic unsent password")
        compose.onNodeWithTag("bitwarden_login_email").performScrollTo().performTextReplacement("second@example.com")
        assertEquals("", compose.onNodeWithTag("bitwarden_login_password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("bitwarden_login_password").performScrollTo().performTextInput("Synthetic second unsent password")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals("", compose.onNodeWithTag("bitwarden_login_password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("bitwarden_server_configure").performScrollTo().performClick()
        compose.onNodeWithText(english.getString(R.string.bitwarden_server_headers)).performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_server_header_0_name").performScrollTo().assertTextContains(PROXY_NAME)
        compose.onNodeWithTag("bitwarden_server_header_0_value").performScrollTo()
        assertEquals(PROXY_VALUE, compose.onNodeWithTag("bitwarden_server_header_0_value").fetchSemanticsNode().config[SemanticsProperties.InputText].text)
        compose.runOnUiThread { SessionManager.markLocked() }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { SessionManager.markUnlocked() }
        compose.onNodeWithTag("bitwarden_server_configure").performScrollTo().performClick()
        compose.onNodeWithText(english.getString(R.string.bitwarden_server_headers)).performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_server_header_0_name").assertDoesNotExist()
        assertTrue(model.loginState.value is BitwardenViewModel.LoginState.Idle)
    }

    private fun model() = BitwardenViewModel(compose.activity.application).also { models.put(UUID.randomUUID().toString(), it) }
    private fun draft(base: MockWebServer, api: MockWebServer, identity: MockWebServer) = BitwardenEnvironmentDraft(
        BitwardenEnvironmentPreset.CUSTOM, base.url("/Reverse/").toString(),
        apiUrl = api.url("/IndependentApi/").toString(), identityUrl = identity.url("/IndependentIdentity/").toString(),
        headers = listOf(BitwardenHeaderDraft(PROXY_NAME, PROXY_VALUE)))
    private fun show(content: @Composable () -> Unit) {
        val configuration = Configuration(english.resources.configuration)
        compose.runOnUiThread {
            originalConfiguration = Configuration(compose.activity.resources.configuration)
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(configuration, compose.activity.resources.displayMetrics)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) { if (visible.value) content() } }
            }
        }
    }
    private fun waitTag(tag: String, timeout: Long = 20_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private companion object {
        const val PROXY_NAME = "X-Synthetic-Proxy"
        const val PROXY_VALUE = "synthetic-server-configuration"
    }
}

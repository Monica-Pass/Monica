package takagi.ru.monica.bitwarden

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher as HttpDispatcher
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
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenRememberedTwoFactorStore
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.LocalVaultRecovery
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Uses Android Keystore-backed preferences, real Repository HTTP and the production MFA checkbox. */
@RunWith(AndroidJUnit4::class)
class BitwardenRememberedMfaInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val english get() = LocaleHelper.setLocale(context, Language.ENGLISH)
    private val models = ViewModelStore()
    private var loginVisible by mutableStateOf(true)

    private var originalAppUnlocked = false
    @Before fun authorizeSyntheticSession() {
        originalAppUnlocked = SessionManager.isUnlocked.value
        compose.runOnUiThread { SessionManager.markUnlocked() }
    }
    @After fun clearModels() { compose.runOnUiThread {
        models.clear()
        if (originalAppUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
    } }

    @Test fun uncheckedNativeRememberSendsZeroAndNeverPersistsTheReturnedToken() {
        scenario { fixture ->
            val model = showPrimaryLogin(fixture)
            submitNativeMfa(remember = false)
            waitForSuccess(model)
            val mfa = fixture.tokenForms.single { it["twoFactorProvider"] == "0" }
            assertEquals("0", mfa["twoFactorRemember"])
            assertEquals("123456", mfa["twoFactorToken"])
            assertNull(fixture.preferences.getString(fixture.rememberedKey, null))
            assertNull(RecoverableBitwardenSettings.open(context).getString(fixture.rememberedKey, null))
            fixture.assertUserKeyDecrypted()
            assertTrue(runBlocking { fixture.repository.logout(fixture.vaultId) })
            assertNull(fixture.preferences.getString(fixture.rememberedKey, null))
        }
    }

    @Test fun checkedNativeRememberIsSealedReusedAsProviderFiveAndRejectedOnlyOnceBeforeFallback() {
        scenario { fixture ->
            val model = showPrimaryLogin(fixture)
            submitNativeMfa(remember = true)
            waitForSuccess(model)
            assertEquals("1", fixture.tokenForms.single { it["twoFactorProvider"] == "0" }["twoFactorRemember"])
            assertEquals(fixture.rememberedToken, RecoverableBitwardenSettings.open(context).getString(fixture.rememberedKey, null))
            val storedName = LocalVaultRecovery(context).activeStore()?.first?.let { "${it}_bitwarden" }
                ?: "bitwarden_secure_prefs"
            val raw = File(context.applicationInfo.dataDir, "shared_prefs/$storedName.xml").readText()
            assertFalse("Remember token was written without sealing", raw.contains(fixture.rememberedToken))
            assertFalse("The account-scoped key must also be encrypted", raw.contains(fixture.rememberedKey))
            val recoveryMirror = SecurityManager(context).getProtectedString("bitwarden_settings_recovery_v1").orEmpty()
            assertFalse(recoveryMirror.contains(fixture.rememberedToken))
            assertFalse(recoveryMirror.contains(fixture.rememberedKey))
            runBlocking { fixture.repository.forceLock(fixture.vaultId) }
            val reused = runBlocking { fixture.repository.login(fixture.endpoint, fixture.email, fixture.password) }
            assertTrue(reused.toString(), reused is BitwardenRepository.RepositoryLoginResult.Success)
            val rememberedRequest = fixture.tokenForms.last()
            assertEquals("5", rememberedRequest["twoFactorProvider"])
            assertEquals(fixture.rememberedToken, rememberedRequest["twoFactorToken"])
            assertEquals(fixture.passwordHash, rememberedRequest["password"])
            fixture.assertUserKeyDecrypted()
            runBlocking { fixture.repository.forceLock(fixture.vaultId) }
            fixture.rejectRemembered = true
            val before = fixture.tokenForms.size
            val rejected = runBlocking { fixture.repository.login(fixture.endpoint, fixture.email, fixture.password) }
            assertTrue(rejected.toString(), rejected is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired)
            val retry = fixture.tokenForms.drop(before)
            assertEquals(2, retry.size)
            assertEquals("5", retry[0]["twoFactorProvider"])
            assertNull(retry[1]["twoFactorProvider"])
            assertNull(fixture.preferences.getString(fixture.rememberedKey, null))
            (rejected as BitwardenRepository.RepositoryLoginResult.TwoFactorRequired).state.clear()
            assertTrue(runBlocking { fixture.repository.logout(fixture.vaultId) })
        }
    }

    @Test fun leavingDuringMfaAndLogoutDuringRememberedLoginPreventLatePublication() {
        scenario { fixture ->
            val model = showPrimaryLogin(fixture)
            fixture.holdProvider = "0"
            submitNativeMfa(remember = true)
            assertTrue("MFA request did not reach synthetic server", fixture.requestStarted.await(10, TimeUnit.SECONDS))
            val inFlight = BitwardenViewModel::class.java.getDeclaredField("authenticationJob")
                .apply { isAccessible = true }.get(model) as Job
            // Removing the same production login screen models navigation away and runs its DisposableEffect.
            compose.runOnIdle { loginVisible = false }
            compose.waitForIdle()
            fixture.releaseResponse.countDown()
            runBlocking { withTimeout(20_000) { inFlight.join() } }
            assertTrue(model.loginState.value is BitwardenViewModel.LoginState.Idle)
            assertFalse(fixture.repository.isVaultUnlocked(fixture.vaultId))
            assertNull(fixture.preferences.getString(fixture.rememberedKey, null))
            assertTrue(runBlocking { fixture.database.bitwardenVaultDao().getVaultById(fixture.vaultId) }!!.isLocked)
            fixture.holdProvider = null
            val ordinary = runBlocking { fixture.repository.login(fixture.endpoint, fixture.email, fixture.password) }
                as BitwardenRepository.RepositoryLoginResult.TwoFactorRequired
            val authenticated = runBlocking { fixture.repository.loginWithTwoFactor(ordinary.state, "123456", 0,
                fixture.endpoint, remember = true) }
            assertTrue(authenticated.toString(), authenticated is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(fixture.rememberedToken, fixture.preferences.getString(fixture.rememberedKey, null))
            fixture.resetBarrier("5")
            runBlocking {
                val late = async(Dispatchers.IO) { fixture.repository.login(fixture.endpoint, fixture.email, fixture.password) }
                assertTrue(fixture.requestStarted.await(10, TimeUnit.SECONDS))
                assertTrue(fixture.repository.logout(fixture.vaultId))
                assertNull(fixture.preferences.getString(fixture.rememberedKey, null))
                fixture.releaseResponse.countDown()
                val completion = withTimeout(20_000) { late.await() }
                assertTrue(completion.toString(), completion is BitwardenRepository.RepositoryLoginResult.Error)
                assertNull(fixture.database.bitwardenVaultDao().getVaultById(fixture.vaultId))
                assertFalse(fixture.repository.isVaultUnlocked(fixture.vaultId))
                assertTrue(fixture.repository.getAllVaults().none { it.email == fixture.email })
            }
        }
    }

    private fun scenario(block: (Fixture) -> Unit) {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try { runBlocking { fixture.initialize() }; block(fixture) }
            finally {
                fixture.releaseResponse.countDown()
                compose.runOnIdle { loginVisible = false; models.clear() }
                runBlocking { fixture.close() }
            }
        }
    }

    private fun showPrimaryLogin(fixture: Fixture): BitwardenViewModel {
        val model = BitwardenViewModel(compose.activity.application).also { models.put("remember-${UUID.randomUUID()}", it) }
        val localized = english
        val configuration = Configuration(localized.resources.configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) {
                    if (loginVisible) BitwardenLoginScreen(model, { loginVisible = false }, {})
                } }
            }
        }
        compose.onNodeWithTag("bitwarden_server_configure").performScrollTo().performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_server_self_hosted)).performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_server_base").performScrollTo().performTextReplacement(fixture.endpoint)
        compose.onNodeWithTag("bitwarden_server_apply").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction() and hasText(localized.getString(R.string.legacy_ui_email_address)))
            .performScrollTo().performTextInput(fixture.email)
        compose.onNode(hasSetTextAction() and hasText(localized.getString(R.string.master_password)))
            .performScrollTo().performTextInput(fixture.password)
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_login)).performScrollTo().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("bitwarden_two_factor_remember").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bitwarden_two_factor_remember").performScrollTo().assertIsOff()
        return model
    }

    private fun submitNativeMfa(remember: Boolean) {
        if (remember) compose.onNodeWithTag("bitwarden_two_factor_remember").performClick().assertIsOn()
        else compose.onNodeWithTag("bitwarden_two_factor_remember").assertIsOff()
        compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("bitwarden_two_factor_submit").assertIsEnabled().performClick()
    }

    private fun waitForSuccess(model: BitwardenViewModel) {
        compose.waitUntil(30_000) { model.loginState.value is BitwardenViewModel.LoginState.Success }
        compose.waitForIdle()
    }

    private inner class Fixture(val server: MockWebServer) {
        private val uuid = UUID.randomUUID().toString()
        val email = "remember-$uuid@example.invalid"
        val password = "Synthetic remembered authentication 42!"
        val rememberedToken = "synthetic-remember-token-$uuid"
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val preferences: SharedPreferences = RecoverableBitwardenSettings.open(context)
        val tokenForms = CopyOnWriteArrayList<Map<String, String>>()
        private val userKey = SymmetricCryptoKey(ByteArray(32) { 23 }, ByteArray(32) { 47 })
        var vaultId = 0L
        lateinit var endpoint: String
        lateinit var rememberedKey: String
        lateinit var passwordHash: String
        private lateinit var wrappedUserKey: String
        private val hadActive = preferences.contains("active_vault_id")
        private val oldActive = preferences.getLong("active_vault_id", -1)
        @Volatile var rejectRemembered = false
        @Volatile var holdProvider: String? = null
        var requestStarted = CountDownLatch(1)
        var releaseResponse = CountDownLatch(1)

        suspend fun initialize() {
            endpoint = server.url("/").toString().trimEnd('/')
            val canonicalServer = BitwardenVaultIdentity.normalizeServerUrl(endpoint)
            val environment = requireNotNull(BitwardenEnvironmentResolver.resolve(
                BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, endpoint), UUID.randomUUID().toString()).value)
            rememberedKey = "remembered_two_factor_v1_" + BitwardenRememberedTwoFactorStore.scope(canonicalServer, email) +
                ":transport-v2:" + environment.fingerprint
            val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, 600_000)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try {
                passwordHash = BitwardenCrypto.deriveMasterPasswordHash(master, password)
                wrappedUserKey = BitwardenCrypto.encrypt(userKey.encKey + userKey.macKey, stretched)
            } finally { master.fill(0); stretched.clear() }
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(email = email, canonicalEmail = email,
                accountKey = BitwardenVaultIdentity.buildAccountKey(canonicalServer, null, email),
                serverUrl = canonicalServer, identityUrl = "$canonicalServer/identity", apiUrl = "$canonicalServer/api",
                isLocked = true, isConnected = false, isDefault = false, syncEnabled = false))
            server.dispatcher = object : HttpDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/identity/accounts/prelogin") return ok("{\"kdf\":0,\"kdfIterations\":600000}")
                    if (path == "/identity/connect/token") {
                        val form = request.body.readUtf8().split('&').associate {
                            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                        }
                        tokenForms += form
                        if (form["username"] != email || form["password"] != passwordHash) return MockResponse().setResponseCode(403)
                        val provider = form["twoFactorProvider"]
                        if (provider != null && provider == holdProvider) {
                            requestStarted.countDown()
                            if (!releaseResponse.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        }
                        return when {
                            provider == "0" && form["twoFactorToken"] == "123456" -> success()
                            provider == "5" && form["twoFactorToken"] == rememberedToken && !rejectRemembered -> success()
                            provider == "5" -> MockResponse().setResponseCode(401)
                                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid remembered token\"}")
                            provider == null -> MockResponse().setResponseCode(400)
                                .setBody("{\"error\":\"invalid_grant\",\"TwoFactorProviders\":[\"0\"],\"TwoFactorProviders2\":{\"0\":{}}}")
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    if (path == "/api/accounts/profile") return ok("{\"id\":\"$uuid\",\"email\":\"$email\",\"securityStamp\":\"synthetic-stamp\"}")
                    if (path == "/api/sync") return ok("{\"Profile\":{\"id\":\"$uuid\",\"email\":\"$email\",\"SecurityStamp\":\"synthetic-stamp\"},\"Ciphers\":[],\"Folders\":[]}")
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        private fun success() = ok(buildJsonObject {
            put("access_token", "synthetic-access-$uuid"); put("refresh_token", "synthetic-refresh-$uuid")
            put("expires_in", 3600); put("Key", wrappedUserKey); put("TwoFactorToken", rememberedToken)
        }.toString())

        fun resetBarrier(provider: String) {
            requestStarted = CountDownLatch(1); releaseResponse = CountDownLatch(1); holdProvider = provider
        }

        fun assertUserKeyDecrypted() {
            val cached = requireNotNull(repository.getCachedSymmetricKey(vaultId))
            try { assertArrayEquals(userKey.encKey, cached.encKey); assertArrayEquals(userKey.macKey, cached.macKey) }
            finally { cached.clear() }
        }

        suspend fun close() {
            if (vaultId != 0L) {
                val row = database.bitwardenVaultDao().getVaultById(vaultId)
                if (row != null) {
                    check(row.email == email && BitwardenVaultIdentity.normalizeServerUrl(row.serverUrl) ==
                        BitwardenVaultIdentity.normalizeServerUrl(endpoint))
                    repository.logout(vaultId)
                }
            }
            if (::rememberedKey.isInitialized) preferences.edit().remove(rememberedKey).commit()
            if (hadActive) preferences.edit().putLong("active_vault_id", oldActive).commit()
            else preferences.edit().remove("active_vault_id").commit()
            userKey.clear()
        }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

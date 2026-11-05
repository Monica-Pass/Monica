package takagi.ru.monica.bitwarden.service

import android.app.Application
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.localization.xmlTestStrings
import takagi.ru.monica.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BitwardenAuthRedactionTest {
    private val secret = "synthetic-private-header-value"

    @Test fun transportClassificationSurvivesWithoutRawCausesOrMessages() {
        val failures = listOf(
            CertificateExpiredException(secret), CertificateNotYetValidException(secret),
            SSLPeerUnverifiedException("Hostname $secret mismatch"), CertificateException(secret),
            SSLHandshakeException("protocol failure $secret"), UnknownHostException(secret),
            SocketTimeoutException(secret), ConnectException(secret),
            IOException("Proxy authentication required $secret"),
            UnknownServiceException("Cleartext traffic not permitted $secret"), IOException(secret)
        )
        failures.forEach { error ->
            error.addSuppressed(Exception(secret))
            val wrapped = IOException("https://$secret.invalid/path", error)
            val sanitized = redactedBitwardenAuthException(wrapped)
            assertEquals(classifyBitwardenLoginError(wrapped), classifyBitwardenLoginError(sanitized))
            assertSafe(sanitized)
        }
    }

    @Test fun serverEchoesMapToFixedCategoriesAndExistingLocalizedMessages() {
        val examples = mapOf(
            "invalid_username_or_password" to BitwardenAuthResponseKind.BAD_LOGIN,
            "Invalid New Device OTP" to BitwardenAuthResponseKind.NEW_DEVICE_CODE,
            "Two-step token is invalid" to BitwardenAuthResponseKind.TWO_FACTOR_CODE,
            "New device verification required" to BitwardenAuthResponseKind.NEW_DEVICE_REQUIRED,
            "captcha required" to BitwardenAuthResponseKind.CAPTCHA_REQUIRED,
            "captcha error sitekey" to BitwardenAuthResponseKind.CAPTCHA,
            "Bitwarden Argon2id KDF memory is too high" to BitwardenAuthResponseKind.KDF_MEMORY,
            "too many attempts" to BitwardenAuthResponseKind.LOCKED,
            "invalid_grant" to BitwardenAuthResponseKind.INVALID_GRANT,
        )
        examples.forEach { (phrase, kind) ->
            val error = redactedBitwardenAuthResponse(400, "$secret $phrase $secret")
            assertEquals(kind, classifyBitwardenAuthResponse(error.message))
            assertSafe(error)
        }
        val strings = xmlTestStrings("zh")
        assertEquals(strings.get(R.string.bitwarden_message_bad_login), BitwardenRepository.parseErrorMessage(
            redactedBitwardenAuthResponse(400, "invalid_username_or_password $secret").message, strings))
        assertEquals(strings.get(R.string.bitwarden_message_server_unavailable), BitwardenRepository.parseErrorMessage(
            redactedBitwardenAuthResponse(503, secret).message, strings))
        assertEquals(strings.get(R.string.bitwarden_message_kdf_memory), BitwardenRepository.parseErrorMessage(
            redactedBitwardenAuthException(IllegalStateException("Bitwarden Argon2id KDF requires $secret")).message, strings))
    }

    @Test fun diagnosticsRejectInjectedLabelsAndRetainOnlyBoundedStatus() {
        val safe = bitwardenAuthDiagnosticLine("primary", "1234abcd", "result_error", 401, BitwardenLoginError.Dns)
        assertEquals("[BW_DIAG] flow=primary attempt=1234abcd stage=result_error code=401 failure=Dns", safe)
        val rejected = bitwardenAuthDiagnosticLine(secret, "1234abcd\n$secret", "https://$secret", Int.MAX_VALUE)
        assertFalse(rejected.contains(secret))
        assertFalse(rejected.contains("1234abcd"))
        assertFalse(rejected.contains("code="))
        assertSafe(redactedBitwardenAuthResponse(Int.MAX_VALUE, "<html>$secret</html>"))
    }

    @Test fun cancellationRemainsCancellation() {
        val cancelled = CancellationException(secret)
        assertSame(cancelled, runCatching { redactedBitwardenAuthException(cancelled) }.exceptionOrNull())
    }

    @Test fun actualLoginRetryNeverReturnsOrLogsServerEchoes() = withServer { server, service, environment ->
        server.enqueue(MockResponse().setBody("""{"kdf":0,"kdfIterations":1}"""))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"invalid_grant","error_description":"invalid_username_or_password $secret"}""")) }
        val error = service.login("$secret@example.invalid", "$secret-password", environment = environment).exceptionOrNull()!!
        assertEquals(BitwardenAuthResponseKind.BAD_LOGIN, classifyBitwardenAuthResponse(error.message))
        assertSafe(error)
        assertEquals(3, server.requestCount)
        assertLogsSafe()
    }

    @Test fun actualCaptchaKeepsChallengeStateButUsesLocalMessage() = withServer { server, service, environment ->
        server.enqueue(MockResponse().setBody("""{"kdf":0,"kdfIterations":1}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"captcha_required","error_description":"captcha $secret","HCaptcha_SiteKey":"synthetic-site-key"}"""))
        val result = service.login("$secret@example.invalid", "synthetic-password", environment = environment).getOrThrow()
        assertTrue(result is LoginResult.CaptchaRequired)
        assertFalse((result as LoginResult.CaptchaRequired).message.contains(secret))
        assertEquals("synthetic-site-key", result.siteKey)
        assertLogsSafe()
    }

    @Test fun actualPreloginAndRefreshFailuresExcludeReasonPhraseAndBody() = withServer { server, service, environment ->
        server.enqueue(MockResponse().setStatus("HTTP/1.1 503 $secret").setBody(secret))
        assertSafe(service.preLogin("$secret@example.invalid", diagnosticAttemptId = secret, environment = environment).exceptionOrNull()!!)
        server.enqueue(MockResponse().setStatus("HTTP/1.1 503 $secret").setBody(secret))
        assertSafe(service.refreshToken(secret, environment = environment).exceptionOrNull()!!)
        assertLogsSafe()
    }

    @Test fun actualTwoFactorNewDeviceAndEmailFailuresNeverReturnServerText() = withServer { server, service, environment ->
        val state = LoginResult.TwoFactorRequired(
            providers = listOf(0, BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE), providersData = null,
            tempMasterKey = ByteArray(32), tempStretchedKey = SymmetricCryptoKey(ByteArray(32), ByteArray(32)),
            email = "$secret@example.invalid", passwordHash = secret, kdfType = 0, kdfIterations = 1,
            kdfMemory = null, kdfParallelism = null, diagnosticAttemptId = secret, environment = environment
        )
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"error":"invalid_grant","error_description":"unknown rejection $secret"}""")) }
            assertSafe(service.loginTwoFactor(state, "123456", 0).exceptionOrNull()!!)
            assertSafe(service.loginNewDeviceOtp(state, "123456").exceptionOrNull()!!)
            assertSafe(service.sendTwoFactorEmailLogin(state).exceptionOrNull()!!)
            assertEquals(3, server.requestCount)
            assertLogsSafe()
        } finally { state.clear() }
    }

    private fun assertSafe(error: Throwable) {
        assertFalse(error.toString().contains(secret))
        assertFalse(error.message.orEmpty().contains("https://"))
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
    }

    private fun assertLogsSafe() {
        val lines = ShadowLog.getLogsForTag("BitwardenAuthService").map { it.msg }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startsWith("[BW_DIAG] flow=") })
        assertTrue(lines.none { it.contains(secret) || it.contains("127.0.0.1") || it.contains("localhost") ||
            it.contains("passwordLen") || it.contains("emailDomain") || it.contains("pw_chars") || it.contains("body=") })
    }

    private fun withServer(block: suspend (MockWebServer, BitwardenAuthService, takagi.ru.monica.bitwarden.api.BitwardenEnvironment) -> Unit) = runBlocking {
        ShadowLog.clear()
        MockWebServer().use { server ->
            val environment = BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
                BitwardenEnvironmentPreset.CUSTOM, server.url("/").toString()
            ), UUID.randomUUID().toString()).value!!
            val manager = BitwardenApiManager()
            try { block(server, BitwardenAuthService(RuntimeEnvironment.getApplication(), manager), environment) }
            finally { manager.clearCache() }
        }
    }
}

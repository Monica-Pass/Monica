package takagi.ru.monica.bitwarden.service

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import takagi.ru.monica.bitwarden.api.BitwardenApiFactory
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitwardenNewDeviceOtpResendTest {
    private val email = "new-device-synthetic@example.test"
    private val proof = "synthetic-new-device-master-password-hash"
    private fun service() = BitwardenAuthService(RuntimeEnvironment.getApplication())
    private fun challenge(provider: Int = BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE) = LoginResult.TwoFactorRequired(
        providers = listOf(provider), providersData = null, tempMasterKey = ByteArray(32),
        tempStretchedKey = SymmetricCryptoKey(ByteArray(32), ByteArray(32)), email = email,
        passwordHash = proof, kdfType = 0, kdfIterations = 600_000, kdfMemory = null,
        kdfParallelism = null, authHeaderProfile = BitwardenApiFactory.HeaderProfile.KEYGUARD_FALLBACK,
        authSessionEpoch = 1
    )

    @Test fun postsOnlyOfficialNewDeviceFieldsWithoutAuthorizationOrAnOrdinaryEmailRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            service().resendNewDeviceOtp(challenge(), server.url("/").toString()).getOrThrow()
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/accounts/resend-new-device-otp", request.path)
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.getHeader("User-Agent")!!.contains("Chrome/126."))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("Email", "MasterPasswordHash"), body.keys)
            assertEquals(email, body["Email"]!!.jsonPrimitive.content)
            assertEquals(proof, body["MasterPasswordHash"]!!.jsonPrimitive.content)
            assertEquals(1, server.requestCount)
            assertNoProofInLogs()
        }
    }

    @Test fun ordinaryEmailChallengeCannotCallNewDeviceResend() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(service().resendNewDeviceOtp(challenge(BitwardenAuthService.TWO_FACTOR_EMAIL), server.url("/").toString()).isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unsupportedServersNeverFallBackAndNeverExposeTheirBody() = runBlocking {
        for (status in listOf(404, 405)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("$email $proof raw-server-error"))
            val error = service().resendNewDeviceOtp(challenge(), server.url("/").toString()).exceptionOrNull() as NewDeviceOtpResendException
            assertEquals(NewDeviceOtpResendFailure.UNAVAILABLE, error.failure)
            assertFalse(error.toString().contains(proof))
            assertNull(error.cause)
            assertEquals(1, server.requestCount)
            assertNoProofInLogs()
        }
    }

    @Test fun rateLimitRetainsOnlyDelayAndFailureCategory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "91")
                .setBody("$email $proof sensitive-rate-limit-detail"))
            val error = service().resendNewDeviceOtp(challenge(), server.url("/").toString()).exceptionOrNull() as NewDeviceOtpResendException
            assertEquals(NewDeviceOtpResendFailure.RATE_LIMITED, error.failure)
            assertEquals(91, error.retryAfterSeconds)
            assertFalse(error.toString().contains("sensitive-rate-limit-detail"))
            assertNoProofInLogs()
        }
    }

    @Test fun genericFailureNeverEchoesCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("$email $proof"))
            val error = service().resendNewDeviceOtp(challenge(), server.url("/").toString()).exceptionOrNull() as NewDeviceOtpResendException
            assertEquals(NewDeviceOtpResendFailure.FAILED, error.failure)
            assertFalse(error.message!!.contains(email))
            assertNull(error.cause)
            assertNoProofInLogs()
        }
    }

    @Test fun cancellationPropagatesWithoutAReplacementRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async { service().resendNewDeviceOtp(challenge(), server.url("/").toString()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retryAfterAcceptsNumericAndHttpDateWithoutOverflow() {
        assertEquals(60, newDeviceOtpRetryAfterSeconds("60"))
        assertEquals(Int.MAX_VALUE, newDeviceOtpRetryAfterSeconds(Long.MAX_VALUE.toString()))
        assertEquals(0, newDeviceOtpRetryAfterSeconds("-1"))
        assertEquals(60, newDeviceOtpRetryAfterSeconds("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertEquals(1, newDeviceOtpRetryAfterSeconds("Thu, 1 Jan 1970 00:00:01 GMT", 1))
        assertNull(newDeviceOtpRetryAfterSeconds("untrusted arbitrary text"))
    }

    private fun assertNoProofInLogs() {
        assertFalse(ShadowLog.getLogs().any { it.msg.contains(proof) || it.msg.contains(email) })
    }
}

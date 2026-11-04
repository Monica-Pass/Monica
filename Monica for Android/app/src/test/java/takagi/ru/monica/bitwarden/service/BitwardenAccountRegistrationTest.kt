package takagi.ru.monica.bitwarden.service

import android.app.Application
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevicesResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitwardenAccountRegistrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val email = "synthetic@example.test"
    private val password = "Synthetic registration 42! unique"
    private fun service() = BitwardenAccountService(RuntimeEnvironment.getApplication())

    @Test fun modernRegistrationVerifiesEmailAndSendsDecryptableFreshKeys() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"emailVerificationToken\":null}"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = service()
            val ticket = client.startRegistration(server.url("/").toString(), email, "Synthetic", null).getOrThrow()
            assertFalse(ticket.readyForPassword)
            val verified = client.verifyRegistration(ticket, "synthetic-email-token").getOrThrow()
            assertTrue(verified.readyForPassword)
            client.finishRegistration(verified, password, password, "synthetic hint").getOrThrow()
            assertEquals("/identity/accounts/register/send-verification-email", server.takeRequest().path)
            val verification = server.takeRequest()
            assertEquals("/identity/accounts/register/verification-email-clicked", verification.path)
            assertEquals("synthetic-email-token", json.parseToJsonElement(verification.body.readUtf8()).jsonObject["emailVerificationToken"]!!.jsonPrimitive.content)
            val registration = server.takeRequest()
            assertEquals("/identity/accounts/register/finish", registration.path)
            val raw = registration.body.readUtf8()
            assertFalse(raw.contains(password))
            val body = json.parseToJsonElement(raw).jsonObject
            val authentication = body["MasterPasswordAuthentication"]!!.jsonObject
            val unlock = body["MasterPasswordUnlock"]!!.jsonObject
            val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, 600_000)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            val userBytes = BitwardenCrypto.decrypt(unlock["masterKeyWrappedUserKey"]!!.jsonPrimitive.content, stretched)
            assertEquals(64, userBytes.size)
            assertEquals(BitwardenCrypto.deriveMasterPasswordHash(master, password), authentication["MasterPasswordAuthenticationHash"]!!.jsonPrimitive.content)
            val userKey = BitwardenCrypto.SymmetricCryptoKey(userBytes.copyOfRange(0, 32), userBytes.copyOfRange(32, 64))
            val asymmetric = body["userAsymmetricKeys"]!!.jsonObject
            val privateBytes = BitwardenCrypto.decrypt(asymmetric["encryptedPrivateKey"]!!.jsonPrimitive.content, userKey)
            val rsa = KeyFactory.getInstance("RSA")
            val privateKey = rsa.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            val publicKey = rsa.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(asymmetric["publicKey"]!!.jsonPrimitive.content)))
            val challenge = "synthetic registration key consistency".toByteArray()
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(challenge) }.sign()
            assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update(challenge) }.verify(signature))
            master.fill(0); stretched.clear(); userBytes.fill(0); userKey.clear(); privateBytes.fill(0)
        }
    }

    @Test fun registrationDisabledErrorNeverFallsBackToLegacy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Registration disabled\"}"))
            assertTrue(service().startRegistration(server.url("/").toString(), email, null, null).isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun legacyFallbackRequiresMissingModernEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = service()
            val ticket = client.startRegistration(server.url("/").toString(), email, null, null).getOrThrow()
            assertTrue(ticket.legacy)
            assertTrue(ticket.readyForPassword)
            client.finishRegistration(ticket, password, password, null).getOrThrow()
            assertEquals("/identity/accounts/register/send-verification-email", server.takeRequest().path)
            val registration = server.takeRequest()
            assertEquals("/api/accounts/register", registration.path)
            val body = json.parseToJsonElement(registration.body.readUtf8()).jsonObject
            assertTrue(body.containsKey("masterPasswordHash"))
            assertTrue(body.containsKey("key"))
            assertTrue(body.containsKey("keys"))
            assertFalse(body.containsKey("MasterPasswordUnlock"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unverifiedOrMismatchedRegistrationDoesNotSendAnAccountRequest() = runBlocking {
        MockWebServer().use { server ->
            val current = BitwardenRegistrationTicket(server.url("/").toString().trimEnd('/'), email, null, null)
            assertTrue(service().finishRegistration(current, password, password, null).isFailure)
            assertTrue(service().finishRegistration(current.copy(verifiedEmailToken = "synthetic-token"), password, "different", null).isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun passwordHintUsesApiEndpointAndSendsNoCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            service().requestPasswordHint(server.url("/").toString(), email, null).getOrThrow()
            val request = server.takeRequest()
            assertEquals("/api/accounts/password-hint", request.path)
            assertEquals(setOf("email"), json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun deviceListDoesNotExposeEncryptedAccountKeyMaterial() {
        val response = json.decodeFromString<BitwardenAccountDevicesResponse>("""{"data":[{"id":"synthetic-device","name":"Synthetic Android","type":0,"isTrusted":true,"encryptedUserKey":"never expose","encryptedPublicKey":"never expose"}]}""")
        assertEquals("synthetic-device", response.data.single().id)
        assertTrue(response.data.single().isTrusted == true)
        val encoded = json.encodeToString(BitwardenAccountDevicesResponse.serializer(), response)
        assertFalse(encoded.contains("never expose"))
        assertFalse(encoded.contains("encryptedUserKey"))
    }
}

package takagi.ru.monica.bitwarden.service

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevicesResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto

/** Task-only synthetic registration parity. Never install/run against production IDs or preserved user 12. */
@RunWith(AndroidJUnit4::class)
class ToolchainAccountRegistrationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clients = mutableListOf<BitwardenAccountService>()
    private var authorizedFixture = false

    @org.junit.Before fun unlockMainSession() = runBlocking {
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd")) {
            "Only the task-owned isolated package IDs are allowed"
        }
        check(android.os.Process.myUid() / 100_000 == 11 && context.applicationInfo.uid == android.os.Process.myUid()) {
            "Only task-owned test user 11 is allowed; reject owner, existing user 10 and preservation user 12"
        }
        authorizedFixture = true
        // Use the existing bounded startup contract. Failure never authorizes recovery or reset.
        val prepared = takagi.ru.monica.security.SecureStorageStartup.prepareForEntry(context, timeoutMillis = 15_000)
        assertTrue("Secure storage must be ready before authorizing the synthetic session",
            prepared is takagi.ru.monica.security.SecureStartupResult.Ready)
        takagi.ru.monica.security.SessionManager.attachAppContext(context)
        takagi.ru.monica.security.SessionManager.markUnlocked()
    }

    @org.junit.After fun lockMainSession() {
        if (!authorizedFixture) return
        var cleanupFailure: Throwable? = null
        try {
            clients.asReversed().forEach { client ->
                try { client.retireAll() }
                catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure!!.addSuppressed(failure)
                }
            }
        } finally {
            clients.clear()
            takagi.ru.monica.security.SessionManager.markLocked()
        }
        cleanupFailure?.let { throw it }
    }

    private fun loopbackServer(): MockWebServer {
        check(authorizedFixture)
        return MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    }

    private fun request(server: MockWebServer): okhttp3.mockwebserver.RecordedRequest =
        checkNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)) {
            "Expected synthetic loopback request was not recorded"
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val email = "synthetic@example.test"
    private val password = "Synthetic registration 42! unique"
    private fun service(): BitwardenAccountService {
        check(authorizedFixture)
        return BitwardenAccountService(context).also { clients += it }
    }

    @Test fun modernRegistrationVerifiesEmailAndSendsDecryptableFreshKeys() = runBlocking {
        loopbackServer().use { server ->
            val responseCodes = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.QueueDispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    super.dispatch(request).also { response ->
                        responseCodes += response.status.split(' ').getOrNull(1) ?: "unknown"
                    }
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"emailVerificationToken\":null}"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = service()
            val initialGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            fun <T> checkedPhase(phase: String, result: Result<T>): T {
                result.exceptionOrNull()?.let { failure ->
                    val runtime = Runtime.getRuntime()
                    throw AssertionError(
                        "Modern registration $phase phase failed; requests=${server.requestCount}; " +
                            "serverDispatchedStatus=$responseCodes; " +
                            "unlocked=${takagi.ru.monica.security.SessionManager.isUnlocked.value}; " +
                            "sameGeneration=${initialGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value}; " +
                            "maxHeapMiB=${runtime.maxMemory() / 1_048_576}; " +
                            "usedHeapMiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}",
                        failure,
                    )
                }
                return result.getOrThrow()
            }
            val ticket = checkedPhase("start", client.startRegistration(server.url("/").toString(), email, "Synthetic", null))
            assertFalse(ticket.readyForPassword)
            val verified = checkedPhase("verify", client.verifyRegistration(ticket, "synthetic-email-token"))
            assertTrue(verified.readyForPassword)
            checkedPhase("finish", client.finishRegistration(verified, password, password, "synthetic hint"))
            assertEquals("/identity/accounts/register/send-verification-email", request(server).path)
            val verification = request(server)
            assertEquals("/identity/accounts/register/verification-email-clicked", verification.path)
            assertEquals("synthetic-email-token", json.parseToJsonElement(verification.body.readUtf8()).jsonObject["emailVerificationToken"]!!.jsonPrimitive.content)
            val registration = request(server)
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
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Registration disabled\"}"))
            assertTrue(service().startRegistration(server.url("/").toString(), email, null, null).isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun legacyFallbackRequiresMissingModernEndpoint() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = service()
            val initialGeneration = takagi.ru.monica.security.SessionManager.lockGeneration.value
            fun <T> checkedPhase(phase: String, result: Result<T>): T {
                result.exceptionOrNull()?.let { failure ->
                    throw AssertionError(
                        "Registration $phase phase failed; requests=${server.requestCount}; " +
                            "unlocked=${takagi.ru.monica.security.SessionManager.isUnlocked.value}; " +
                            "sameGeneration=${initialGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value}",
                        failure,
                    )
                }
                return result.getOrThrow()
            }
            val ticket = checkedPhase("start", client.startRegistration(server.url("/").toString(), email, null, null))
            assertTrue(ticket.legacy)
            assertTrue(ticket.readyForPassword)
            checkedPhase("finish", client.finishRegistration(ticket, password, password, null))
            assertEquals("/identity/accounts/register/send-verification-email", request(server).path)
            val registration = request(server)
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
        loopbackServer().use { server ->
            val current = BitwardenRegistrationTicket(server.url("/").toString().trimEnd('/'), email, null, null)
            assertTrue(service().finishRegistration(current, password, password, null).isFailure)
            assertTrue(service().finishRegistration(current.copy(verifiedEmailToken = "synthetic-token"), password, "different", null).isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun passwordHintUsesApiEndpointAndSendsNoCredentials() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            service().requestPasswordHint(server.url("/").toString(), email, null).getOrThrow()
            val request = request(server)
            assertEquals("/api/accounts/password-hint", request.path)
            assertEquals(setOf("email"), json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun independentIdentityWebAndApiUseTheCapturedEnvironment() = runBlocking {
        loopbackServer().use { base -> loopbackServer().use { identity -> loopbackServer().use { api ->
            val environment = requireNotNull(takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.resolve(
                takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft(
                    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.CUSTOM,
                    base.url("/Base/").toString(), webUrl = "https://web.example.test/Team/",
                    identityUrl = identity.url("/Identity/").toString(), apiUrl = api.url("/Api/").toString(),
                    headers = listOf(takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft("X-Proxy-Secret", " synthetic secret "))),
                java.util.UUID.randomUUID().toString()).value)
            identity.enqueue(MockResponse().setBody("{}"))
            identity.enqueue(MockResponse().setResponseCode(204))
            val client = service()
            val ticket = client.startRegistration(environment, email, null).getOrThrow()
            assertSame(environment, ticket.environment)
            assertTrue(client.verifyRegistration(ticket, base.url("/?token=invalid").toString()).isFailure)
            assertTrue(client.verifyRegistration(ticket, "https://web.example.test/Other/?token=invalid").isFailure)
            client.verifyRegistration(ticket, "https://web.example.test/Team/#/register?token=synthetic").getOrThrow()
            assertEquals(0, base.requestCount)
            assertEquals("/Identity/accounts/register/send-verification-email", request(identity).path)
            val verify = request(identity)
            assertEquals("/Identity/accounts/register/verification-email-clicked", verify.path)
            assertEquals("synthetic secret", verify.getHeader("X-Proxy-Secret"))
            assertNull(verify.getHeader("Authorization"))
            api.enqueue(MockResponse().setResponseCode(204))
            val hintEnvironment = requireNotNull(takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.resolve(
                takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft(
                    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.CUSTOM, base.url("/Base/").toString(),
                    apiUrl = api.url("/Api/").toString()), java.util.UUID.randomUUID().toString()).value)
            client.requestPasswordHint(hintEnvironment, email).getOrThrow()
            assertEquals("/Api/accounts/password-hint", request(api).path)
            client.retire(environment)
            assertTrue(client.verifyRegistration(ticket, "synthetic").isFailure)
            assertEquals(2, identity.requestCount)
        } } }
    }

    @Test fun mainLockAndRetiredEnvironmentPreventRegistrationReuse() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = service()
            val ticket = client.startRegistration(server.url("/").toString(), email, null, null).getOrThrow()
            takagi.ru.monica.security.SessionManager.markLocked()
            takagi.ru.monica.security.SessionManager.markUnlocked()
            assertTrue(client.verifyRegistration(ticket, "synthetic-token").isFailure)
            assertEquals(1, server.requestCount)
            client.retireAll()
        }
    }

    @Test fun serverEchoIsNeverReturnedAsRegistrationError() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"synthetic-proxy-secret\"}"))
            val failure = service().startRegistration(server.url("/").toString(), email, null, null).exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure.toString().contains("synthetic-proxy-secret"))
            assertNull(failure!!.cause)
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

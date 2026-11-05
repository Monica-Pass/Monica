package takagi.ru.monica.bitwarden

import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher as HttpDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Real Repository login, account client, refresh and Room publication against owned localhost TLS. */
@RunWith(AndroidJUnit4::class)
class BitwardenAuthenticatedRepositoryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun concurrentOldBearerRequestsShareOneRefreshAndPublishRotatedRoomCredentials(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val original = f.loginSuccessfully()
            val generation = f.repository.getVaultSessionGeneration(original.id)
            // Four requests fit OkHttp's per-host limit; all must reach the old-token response barrier.
            f.oldRequestWave = CountDownLatch(4)
            val handles = List(4) { f.attachmentContext(original) }
            try {
                val replies = withTimeout(30_000) {
                    handles.map { handle -> async(Dispatchers.IO) {
                        handle.vaultApi.getCipher("Bearer ${handle.accessToken}", CIPHER_ID)
                    } }.awaitAll()
                }
                replies.forEach { response ->
                    response.errorBody()?.close()
                    assertTrue(response.isSuccessful)
                    assertEquals(CIPHER_ID, response.body()?.id)
                    assertEquals(f.encryptedCipherName, response.body()?.name)
                }
                assertEquals(4, f.oldCipherRequests.get())
                assertEquals(4, f.newCipherRequests.get())
                assertEquals(1, f.refreshRequests.get())
                assertEquals(listOf(f.initialRefreshToken), f.refreshTokens.toList())
                f.assertRotatedRow(original)
                assertEquals(generation, f.repository.getVaultSessionGeneration(original.id))
                f.assertUserKey(original.id)

                // An in-flight caller's original bearer alias now uses the published current token.
                val extra = handles.first().vaultApi.getCipher("Bearer ${handles.first().accessToken}", CIPHER_ID)
                extra.errorBody()?.close()
                assertTrue(extra.isSuccessful)
                assertEquals(4, f.oldCipherRequests.get())
                assertEquals(5, f.newCipherRequests.get())
                assertEquals(1, f.refreshRequests.get())
            } finally { handles.forEach { it.wrappingKey.clear() } }
        } }
    }

    @Test fun aSecondUnauthorizedResponseIsReturnedWithoutAnotherRefreshOrApiReplay(): Unit = runBlocking {
        withFixture { f ->
            val original = f.loginSuccessfully()
            f.alwaysUnauthorized = true
            val handle = f.attachmentContext(original)
            try {
                val response = withTimeout(30_000) {
                    handle.vaultApi.getCipher("Bearer ${handle.accessToken}", CIPHER_ID)
                }
                try { assertEquals(401, response.code()) } finally { response.errorBody()?.close() }
                assertEquals(1, f.oldCipherRequests.get())
                assertEquals(1, f.newCipherRequests.get())
                assertEquals(1, f.refreshRequests.get())
                f.assertRotatedRow(original)
                f.assertUserKey(original.id)
            } finally { handle.wrappingKey.clear() }
        }
    }

    @Test fun lateRefreshCannotReplaceTheLocallyReopenedVaultAfterAnAccountLock(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val original = f.loginSuccessfully()
            val handle = f.attachmentContext(original)
            val held = HeldRefresh().also { f.heldRefresh = it }
            val pending = async(Dispatchers.IO) { runCatching {
                handle.vaultApi.getCipher("Bearer ${handle.accessToken}", CIPHER_ID)
            } }
            try {
                held.awaitEntered()
                f.repository.forceLock(original.id)
                val locked = f.row()
                assertTrue(locked.isLocked)
                assertEquals(original.encryptedMasterKey, locked.encryptedMasterKey)
                assertEquals(original.encryptedEncKey, locked.encryptedEncKey)
                assertEquals(original.encryptedMacKey, locked.encryptedMacKey)
                assertTrue(f.repository.unlock(original.id, PASSWORD) is BitwardenRepository.UnlockResult.Success)
                val reopened = f.row()
                f.assertUserKey(original.id)
                held.release.countDown()
                val result = withTimeout(30_000) { pending.await() }
                result.getOrNull()?.errorBody()?.close()
                assertFalse("Revoked refresh must not complete the old API call successfully", result.getOrNull()?.isSuccessful == true)
                assertEquals(reopened, f.row())
                assertEquals(original.encryptedAccessToken, f.row().encryptedAccessToken)
                assertEquals(original.encryptedRefreshToken, f.row().encryptedRefreshToken)
                assertEquals(1, f.refreshRequests.get())
                assertEquals(0, f.newCipherRequests.get())
                assertTrue(f.repository.isVaultUnlocked(original.id))
                f.assertUserKey(original.id)
            } finally { held.release.countDown(); pending.cancelAndJoin(); handle.wrappingKey.clear() }
        } }
    }

    @Test fun lateRefreshCannotPublishAcrossGlobalLockAndUnlockEvenWhenTheRowIsUnchanged(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val original = f.loginSuccessfully()
            val handle = f.attachmentContext(original)
            val held = HeldRefresh().also { f.heldRefresh = it }
            val pending = async(Dispatchers.IO) { runCatching {
                handle.vaultApi.getCipher("Bearer ${handle.accessToken}", CIPHER_ID)
            } }
            try {
                held.awaitEntered()
                f.resetMainSession()
                held.release.countDown()
                val result = withTimeout(30_000) { pending.await() }
                result.getOrNull()?.errorBody()?.close()
                assertFalse("A new global unlock must not adopt an older refresh", result.getOrNull()?.isSuccessful == true)
                assertEquals(original, f.row())
                assertFalse(f.repository.isVaultUnlocked(original.id))
                assertEquals(1, f.refreshRequests.get())
                assertEquals(0, f.newCipherRequests.get())
                assertTrue(f.repository.unlock(original.id, PASSWORD) is BitwardenRepository.UnlockResult.Success)
                assertEquals(original.encryptedAccessToken, f.row().encryptedAccessToken)
                assertEquals(original.encryptedRefreshToken, f.row().encryptedRefreshToken)
                f.assertUserKey(original.id)
            } finally { held.release.countDown(); pending.cancelAndJoin(); handle.wrappingKey.clear() }
        } }
    }

    @Test fun anIoFailureAfterReceivingAWriteDoesNotRestartTheOperationOrRefresh(): Unit = runBlocking {
        withFixture { f ->
            val original = f.loginSuccessfully()
            f.writeFailure = WriteFailure.DISCONNECT
            val handle = f.attachmentContext(original)
            try {
                val result = withTimeout(30_000) { runCatching {
                    handle.vaultApi.createCipherDocument("Bearer ${handle.accessToken}", f.writeDocument())
                } }
                result.getOrNull()?.errorBody()?.close()
                assertTrue("An uncertain write must surface the transport failure", result.exceptionOrNull() is IOException)
                f.assertSingleUnrefreshedWrite(original)
            } finally { handle.wrappingKey.clear() }
        }
    }

    @Test fun serviceUnavailableOnAWriteDoesNotRestartTheOperationOrRefresh(): Unit = runBlocking {
        withFixture { f ->
            val original = f.loginSuccessfully()
            f.writeFailure = WriteFailure.SERVICE_UNAVAILABLE
            val handle = f.attachmentContext(original)
            try {
                val response = withTimeout(30_000) {
                    handle.vaultApi.createCipherDocument("Bearer ${handle.accessToken}", f.writeDocument())
                }
                try { assertEquals(503, response.code()) } finally { response.errorBody()?.close() }
                f.assertSingleUnrefreshedWrite(original)
            } finally { handle.wrappingKey.clear() }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) = withContext(Dispatchers.IO) {
        val fixture = Fixture()
        var failure: Throwable? = null
        try { fixture.initialize(); block(fixture) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { fixture.close() }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private enum class WriteFailure { DISCONNECT, SERVICE_UNAVAILABLE }

    private class HeldRefresh {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitEntered() = assertTrue("Refresh never reached the owned TLS server", entered.await(25, TimeUnit.SECONDS))
    }

    private inner class Fixture {
        val repository = BitwardenRepository(context)
        private val database = PasswordDatabase.getDatabase(context)
        private val dao = database.bitwardenVaultDao()
        private val security = SecurityManager(context)
        private val repositoryInstance = BitwardenRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private var originalRepository: BitwardenRepository? = null
        private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        private val originalMdk = (mdkField.get(null) as? ByteArray)?.copyOf()
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val preferences: SharedPreferences = RecoverableBitwardenSettings.open(context)
        private val previousPrefs = listOf(ACTIVE, LAST_SYNC).associateWith { preferences.contains(it) to preferences.getLong(it, 0L) }
        private val server = MockWebServer()
        private val protectedReferences = CopyOnWriteArrayList<String>()
        private val userId = UUID.randomUUID().toString()
        private val email = "authenticated-repository-$userId@example.invalid"
        private val userKey = SymmetricCryptoKey(ByteArray(32) { 37 }, ByteArray(32) { 73 })
        private val initialAccessToken = "synthetic-access-$userId-1"
        private val rotatedAccessToken = "synthetic-access-$userId-2"
        val initialRefreshToken = "synthetic-refresh-$userId-1"
        private val rotatedRefreshToken = "synthetic-refresh-$userId-2"
        val refreshRequests = AtomicInteger()
        val oldCipherRequests = AtomicInteger()
        val newCipherRequests = AtomicInteger()
        val refreshTokens = CopyOnWriteArrayList<String>()
        private val writes = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var oldRequestWave: CountDownLatch? = null
        @Volatile var alwaysUnauthorized = false
        @Volatile var heldRefresh: HeldRefresh? = null
        @Volatile var writeFailure = WriteFailure.SERVICE_UNAVAILABLE
        private var initialized = false
        private var started = false
        private lateinit var endpoint: String
        private lateinit var pem: String
        private lateinit var passwordHash: String
        private lateinit var wrappedUserKey: String
        lateinit var encryptedCipherName: String

        suspend fun initialize() {
            val currentRepository = BitwardenRepository.getInstance(context)
            check(currentRepository.getAllVaults().none { currentRepository.isVaultUnlocked(it.id) }) {
                "Run only in a dedicated synthetic Android user without unlocked accounts"
            }
            originalRepository = currentRepository
            repositoryInstance.set(null, repository)
            initialized = true
            val protectedStore = BitwardenTransportSecretStore(
                read = security::getProtectedString,
                write = { reference, value -> protectedReferences += reference; security.putProtectedString(reference, value) },
                remove = security::removeProtectedString,
            )
            // Track only test-owned transport material; every write still uses production protected storage.
            BitwardenRepository::class.java.getDeclaredField("transportSecrets\$delegate").apply { isAccessible = true }
                .set(repository, lazyOf(protectedStore))
            instrumentation.runOnMainSync { SessionManager.markUnlocked() }
            mdkField.set(null, ByteArray(32) { 89 })
            val identity = createTlsIdentity()
            pem = identity.second
            server.useHttps(identity.first.socketFactory, false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            started = true
            endpoint = server.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
            val master = BitwardenCrypto.deriveMasterKeyPbkdf2(PASSWORD, email, ITERATIONS)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try {
                passwordHash = BitwardenCrypto.deriveMasterPasswordHash(master, PASSWORD)
                val bytes = userKey.encKey + userKey.macKey
                try { wrappedUserKey = BitwardenCrypto.encrypt(bytes, stretched) } finally { bytes.fill(0) }
                encryptedCipherName = BitwardenCrypto.encryptString("Synthetic API result", userKey)
            } finally { master.fill(0); stretched.clear() }
            server.dispatcher = object : HttpDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader(HEADER_NAME) != HEADER_VALUE) return MockResponse().setResponseCode(403)
                    return when (request.requestUrl?.encodedPath) {
                        PRELOGIN -> ok("{\"kdf\":0,\"kdfIterations\":$ITERATIONS}")
                        TOKEN -> token(request)
                        PROFILE -> ok(buildJsonObject {
                            put("id", userId); put("email", email); put("securityStamp", "synthetic-stamp-$userId")
                            put("key", wrappedUserKey)
                        }.toString())
                        CIPHER -> cipher(request)
                        CIPHERS -> write(request)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        private fun token(request: RecordedRequest): MockResponse {
            val form = request.body.clone().readUtf8().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val refresh = form["grant_type"] == "refresh_token"
            if (refresh) {
                refreshRequests.incrementAndGet()
                refreshTokens += form["refresh_token"].orEmpty()
                if (form["refresh_token"] != initialRefreshToken) return MockResponse().setResponseCode(400)
                heldRefresh?.let {
                    it.entered.countDown()
                    if (!it.release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                }
            } else if (form["grant_type"] != "password" || form["username"] != email || form["password"] != passwordHash) {
                return MockResponse().setResponseCode(403)
            }
            return ok(buildJsonObject {
                put("access_token", if (refresh) rotatedAccessToken else initialAccessToken)
                put("refresh_token", if (refresh) rotatedRefreshToken else initialRefreshToken)
                put("expires_in", 3600); put("Key", wrappedUserKey)
            }.toString())
        }

        private fun cipher(request: RecordedRequest): MockResponse {
            if (request.method != "GET") return MockResponse().setResponseCode(405)
            when (request.getHeader("Authorization")) {
                "Bearer $initialAccessToken" -> {
                    oldCipherRequests.incrementAndGet()
                    oldRequestWave?.let {
                        it.countDown()
                        if (!it.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    }
                    return MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
                }
                "Bearer $rotatedAccessToken" -> newCipherRequests.incrementAndGet()
                else -> return MockResponse().setResponseCode(403)
            }
            if (alwaysUnauthorized) return MockResponse().setResponseCode(401).setBody("{\"error\":\"still_expired\"}")
            return ok(buildJsonObject { put("id", CIPHER_ID); put("type", 1); put("name", encryptedCipherName) }.toString())
        }

        private fun write(request: RecordedRequest): MockResponse {
            if (request.method != "POST") return MockResponse().setResponseCode(405)
            writes += request
            // An accidental retry receives a success, so it cannot disguise itself as the first failure.
            if (writes.size > 1) return ok("{\"id\":\"unexpected-replayed-write\"}")
            return when (writeFailure) {
                WriteFailure.DISCONNECT -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                // Zero would trigger OkHttp's own replay unless the production body is one-shot.
                WriteFailure.SERVICE_UNAVAILABLE -> MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                    .setBody("{\"error\":\"unavailable\"}")
            }
        }

        suspend fun loginSuccessfully(): BitwardenVault {
            val environment = requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
                BitwardenEnvironmentPreset.CUSTOM, baseUrl = endpoint, apiUrl = "$endpoint/api/",
                identityUrl = "$endpoint/identity/", webUrl = "$endpoint/web/", notificationsUrl = "$endpoint/notifications/",
                headers = listOf(BitwardenHeaderDraft(HEADER_NAME, HEADER_VALUE)), tls = BitwardenTlsConfig(caCertificatePem = pem),
            ), UUID.randomUUID().toString()).value)
            val result = repository.login(endpoint, email, PASSWORD, environment = environment)
            assertTrue("Real synthetic TLS login must publish its account", result is BitwardenRepository.RepositoryLoginResult.Success)
            return row().also {
                assertEquals(initialAccessToken, decode(it.encryptedAccessToken))
                assertEquals(initialRefreshToken, decode(it.encryptedRefreshToken))
                assertUserKey(it.id)
            }
        }

        fun attachmentContext(vault: BitwardenVault): AttachmentFacade.BitwardenContext =
            requireNotNull(repository.getAttachmentBitwardenContext(vault, CIPHER_ID))

        suspend fun row(): BitwardenVault = dao.getAllVaults().filter { it.email == email }.single()

        suspend fun assertRotatedRow(before: BitwardenVault) {
            val after = row()
            assertEquals(rotatedAccessToken, decode(after.encryptedAccessToken))
            assertEquals(rotatedRefreshToken, decode(after.encryptedRefreshToken))
            assertTrue(requireNotNull(after.accessTokenExpiresAt) > System.currentTimeMillis() + 3_000_000)
            assertEquals("Refresh may replace only credentials, expiry and update time", before.copy(
                encryptedAccessToken = after.encryptedAccessToken, encryptedRefreshToken = after.encryptedRefreshToken,
                accessTokenExpiresAt = after.accessTokenExpiresAt, updatedAt = after.updatedAt), after)
        }

        suspend fun assertSingleUnrefreshedWrite(original: BitwardenVault) {
            assertEquals(1, writes.size)
            assertEquals("Bearer $initialAccessToken", writes.single().getHeader("Authorization"))
            assertEquals(writeDocument().toString(), writes.single().body.clone().readUtf8())
            assertEquals(0, refreshRequests.get())
            assertEquals(0, oldCipherRequests.get())
            assertEquals(0, newCipherRequests.get())
            assertEquals(original, row())
            assertUserKey(original.id)
        }

        fun writeDocument() = buildJsonObject { put("type", 1); put("name", encryptedCipherName) }

        fun assertUserKey(vaultId: Long) {
            val key = requireNotNull(repository.getCachedSymmetricKey(vaultId))
            try { assertArrayEquals(userKey.encKey, key.encKey); assertArrayEquals(userKey.macKey, key.macKey) }
            finally { key.clear() }
        }

        fun resetMainSession() {
            instrumentation.runOnMainSync { SessionManager.markLocked(); SessionManager.markUnlocked() }
            mdkField.set(null, ByteArray(32) { 89 })
        }

        suspend fun close() {
            heldRefresh?.release?.countDown()
            oldRequestWave?.let { while (it.count > 0) it.countDown() }
            try {
                if (initialized) {
                    instrumentation.runOnMainSync { SessionManager.markUnlocked() }
                    mdkField.set(null, ByteArray(32) { 89 })
                    dao.getAllVaults().filter { it.email == email }.forEach {
                        check(it.serverUrl.trimEnd('/') == endpoint)
                        check(repository.logout(it.id)) { "Could not remove owned authenticated Repository fixture" }
                    }
                    protectedReferences.forEach { security.removeProtectedString(it) }
                }
            } finally {
                try { if (started) server.shutdown() }
                finally {
                    userKey.clear()
                    if (initialized) {
                        repositoryInstance.set(null, originalRepository)
                        val edit = preferences.edit()
                        previousPrefs.forEach { (name, old) -> if (old.first) edit.putLong(name, old.second) else edit.remove(name) }
                        try { check(edit.commit()) }
                        finally {
                            instrumentation.runOnMainSync {
                                if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
                            }
                            mdkField.set(null, originalMdk)
                        }
                    }
                }
            }
        }
    }

    private fun decode(value: String?) = String(Base64.decode(requireNotNull(value), Base64.NO_WRAP), Charsets.UTF_8)
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    /** Copied fixture pattern uses existing ASN.1 dependencies, without installing a global CA. */
    private fun createTlsIdentity(): Pair<SSLContext, String> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name("CN=Monica Synthetic Authenticated Repository")
        fun sequence(vararg values: ASN1Encodable) = DERSequence(values)
        val extensions = Extensions(arrayOf(
            Extension(Extension.basicConstraints, true, DEROctetString(BasicConstraints(true).encoded)),
            Extension(Extension.subjectAlternativeName, false,
                DEROctetString(GeneralNames(GeneralName(GeneralName.dNSName, "localhost")).encoded)),
        ))
        val tbs = sequence(DERTaggedObject(true, 0, ASN1Integer(2)), ASN1Integer(1L), algorithm, subject,
            sequence(Time(Date(1_577_836_800_000L)), Time(Date(4_102_444_800_000L))), subject,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded), DERTaggedObject(true, 3, extensions))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs.encoded) }.sign()
        val bytes = sequence(tbs, algorithm, DERBitString(signature)).encoded
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        certificate.verify(pair.public)
        val password = "synthetic-repository-server".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password); setKeyEntry("server", pair.private, password, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        password.fill('\u0000')
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
        return tls to pem
    }

    private companion object {
        const val PASSWORD = "Synthetic authenticated repository 42!"
        const val ITERATIONS = 600_000
        const val CIPHER_ID = "synthetic-cipher"
        const val CIPHER = "/api/ciphers/$CIPHER_ID"
        const val CIPHERS = "/api/ciphers"
        const val TOKEN = "/identity/connect/token"
        const val PRELOGIN = "/identity/accounts/prelogin"
        const val PROFILE = "/api/accounts/profile"
        const val HEADER_NAME = "X-Monica-Synthetic-Proxy"
        const val HEADER_VALUE = "synthetic-authenticated-repository"
        const val ACTIVE = "active_vault_id"
        const val LAST_SYNC = "last_sync_time"
    }
}

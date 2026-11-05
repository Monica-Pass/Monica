package takagi.ru.monica.bitwarden

import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher as HttpDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.bitwarden.api.BitwardenVaultEnvironmentStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Production repository, TLS, KDF, Room and encrypted preferences; synthetic accounts only. */
@RunWith(AndroidJUnit4::class)
class BitwardenTransportAuthInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun fullSyncIdentityPromotionAndReloginKeepTheSameProtectedVault(): Unit = runBlocking {
        withFixture { f ->
            val first = f.loginSuccessfully()
            assertNotNull(first.transportSecretRef)
            assertNull(first.tlsCaCertificatePem)
            assertNull(first.tlsClientCertPkcs12Base64)
            assertNull(first.tlsEncryptedClientCertPassword)
            val firstEnvironment = f.resolve(first)
            assertEquals(f.endpoint + "/api/", firstEnvironment.api.toString())
            assertEquals(f.endpoint + "/identity/", firstEnvironment.identity.toString())
            assertEquals(f.endpoint + "/web/", firstEnvironment.web.toString())
            assertEquals(HEADER_VALUE, firstEnvironment.headers.single().value)
            f.assertUserKey()

            assertTrue("The production full sync must accept the synthetic empty vault",
                f.repository.sync(first.id) is BitwardenRepository.SyncResult.Success)
            val promoted = f.row()
            assertEquals(first.id, promoted.id)
            assertEquals(f.userId, promoted.userId)
            assertNotEquals(first.accountKey, promoted.accountKey)
            assertEquals(BitwardenVaultIdentity.buildAccountKey(promoted.serverUrl, f.userId, f.email), promoted.accountKey)
            assertEquals(first.transportSecretRef, promoted.transportSecretRef)
            assertEquals(firstEnvironment.fingerprint, f.resolve(promoted).fingerprint)
            assertTrue(f.paths.contains(SYNC))

            val repeated = f.loginSuccessfully()
            assertEquals(first.id, repeated.id)
            assertEquals(1, f.rows().size)
            assertEquals(promoted.accountKey, repeated.accountKey)
            assertEquals(first.transportRevision, repeated.transportRevision)
            assertEquals(first.transportSecretRef, repeated.transportSecretRef)
            assertNotNull("Identical authenticated transport must retain its attachment-recovery binding",
                f.security.getProtectedString(requireNotNull(first.transportSecretRef)))
            assertEquals(firstEnvironment.fingerprint, f.resolve(repeated).fingerprint)
            f.assertUserKey()
            assertTrue(f.requests.all { it.getHeader(HEADER_NAME) == HEADER_VALUE && it.handshake != null })
            assertFalse(f.paths.any { it.startsWith("/web/") })
        }
    }

    @Test fun changedFingerprintAndMissingProtectedReferenceFailBeforeAnyHttp(): Unit = runBlocking {
        withFixture { f ->
            val valid = f.loginSuccessfully()
            val alternatives = listOf(
                valid.copy(apiUrl = f.endpoint + "/unapproved-api/"),
                valid.copy(environmentPreset = BitwardenEnvironmentPreset.US.name),
                valid.copy(transportSecretRef = BitwardenTransportSecretStore.PREFIX + UUID.randomUUID()),
            )
            alternatives.forEach { changed ->
                f.dao.update(changed)
                val before = f.paths.size
                assertTrue("A changed configuration must fail closed at saved-vault resolution",
                    runCatching { f.resolve(changed) }.isFailure)
                assertTrue("Production sync must not silently fall back to legacy TLS/endpoints",
                    f.repository.sync(valid.id) is BitwardenRepository.SyncResult.Error)
                assertEquals("No HTTP may reach the saved or substituted destination", before, f.paths.size)
                assertEquals(changed, f.row())
                f.dao.update(valid)
            }
            assertTrue("Restoring the original row must retain the usable protected payload",
                f.repository.sync(valid.id) is BitwardenRepository.SyncResult.Success)
        }
    }

    @Test fun failedProtectedReadbackPreservesExistingAccountAndLeavesNewReservationInert(): Unit = runBlocking {
        withFixture { f ->
            val before = f.loginSuccessfully()
            val oldProtected = f.security.getProtectedString(requireNotNull(before.transportSecretRef))
            f.failNextStageReadback.set(true)
            assertLoginRejected(f.login(f.environment("/candidate-api/")))
            assertEquals("A failed stage cannot replace any existing row fields", before, f.row())
            assertEquals(oldProtected, f.security.getProtectedString(before.transportSecretRef!!))
            assertTrue(f.repository.isVaultUnlocked(before.id))
            f.assertUserKey()
            assertEquals(1, f.failedReadbacks.get())
        }
        withFixture { f ->
            f.failNextStageReadback.set(true)
            assertLoginRejected(f.login())
            val reservation = f.rows().single()
            assertTrue(reservation.isLocked)
            assertFalse(reservation.isConnected)
            assertNull(reservation.transportSecretRef)
            assertEquals("", reservation.transportRevision)
            assertNull(reservation.encryptedAccessToken)
            assertNull(reservation.encryptedRefreshToken)
            assertNull(reservation.encryptedMasterKey)
            assertNull(reservation.encryptedEncKey)
            assertNull(reservation.encryptedMacKey)
            assertFalse(f.repository.isVaultUnlocked(reservation.id))
            assertEquals(1, f.failedReadbacks.get())
        }
    }

    @Test fun delayedPrimaryLoginCannotPublishAcrossGlobalLockAndUnlock(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val held = f.hold(TOKEN)
            val pending = async(Dispatchers.IO) { runCatching { f.login() } }
            try {
                held.awaitEntered()
                assertTrue(f.rows().isEmpty())
                f.resetMainSession()
                held.release.countDown()
                assertLoginRejected(withTimeout(30_000) { pending.await() }.getOrNull())
                assertTrue("An obsolete authentication result cannot even reserve a vault", f.rows().isEmpty())
                assertTrue(f.protectedReferences.isEmpty())
                assertTrue(f.paths.contains(TOKEN))
            } finally { held.release.countDown(); pending.cancelAndJoin() }
        } }
    }

    @Test fun expiredReauthenticationContextRejectsLoginBeforeAnyHttp(): Unit = runBlocking {
        withFixture { f ->
            val first = f.loginSuccessfully()
            f.repository.forceLock(first.id)
            val guard = f.reauthenticationContext(first.id)
            assertTrue(f.repository.isReauthenticationCurrent(guard))
            f.repository.forceLock(first.id)
            assertFalse(f.repository.isReauthenticationCurrent(guard))
            val locked = f.row()
            val oldProtected = f.security.getProtectedString(requireNotNull(locked.transportSecretRef))
            val before = f.paths.size

            val result = f.login(f.environment(apiPath = "/candidate-api/"), guard)

            assertTrue("Expired edit authorization must reject before starting authentication",
                result is BitwardenRepository.RepositoryLoginResult.Error)
            assertEquals("Neither prelogin nor token HTTP may leave for an expired edit", before, f.paths.size)
            assertEquals(locked, f.row())
            assertEquals(oldProtected, f.security.getProtectedString(locked.transportSecretRef!!))
            assertEquals(1, f.rows().size)
            assertEquals(1, f.protectedReferences.size)
            assertFalse(f.repository.isVaultUnlocked(first.id))
        }
    }

    @Test fun forceLockAfterReauthenticationMfaRejectsContinuationBeforeAnyHttp(): Unit = runBlocking {
        withFixture { f ->
            val first = f.loginSuccessfully()
            f.repository.forceLock(first.id)
            val guard = f.reauthenticationContext(first.id)
            val candidate = f.environment(apiPath = "/candidate-api/")
            f.requireMfa = true
            val primary = f.login(candidate, guard)
            assertTrue("The real password flow must first form an MFA challenge",
                primary is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired)
            val challenge = (primary as BitwardenRepository.RepositoryLoginResult.TwoFactorRequired).state
            try {
                assertEquals(listOf(0), challenge.providers)
                assertEquals(candidate.revision, challenge.environment?.revision)
                assertTrue(f.repository.isReauthenticationCurrent(guard))
                f.repository.forceLock(first.id)
                assertFalse(f.repository.isReauthenticationCurrent(guard))
                val locked = f.row()
                val oldProtected = f.security.getProtectedString(requireNotNull(locked.transportSecretRef))
                val before = f.paths.size

                val result = f.repository.loginWithTwoFactor(
                    twoFactorState = challenge, twoFactorCode = "123456", twoFactorProvider = 0,
                    serverUrl = f.endpoint,
                )

                assertTrue("A formed challenge cannot authorize an edit after forceLock",
                    result is BitwardenRepository.RepositoryLoginResult.Error)
                assertEquals("Revoked MFA cannot send another token or profile request", before, f.paths.size)
                assertEquals(locked, f.row())
                assertEquals(oldProtected, f.security.getProtectedString(locked.transportSecretRef!!))
                assertEquals(1, f.rows().size)
                assertEquals(1, f.protectedReferences.size)
                assertFalse(f.repository.isVaultUnlocked(first.id))
                assertFalse(f.paths.contains("/candidate-api/accounts/profile"))
            } finally { challenge.clear() }
        }
    }

    @Test fun validReauthenticationUpdatesSameVaultAcrossItsOwnPublicationGeneration(): Unit = runBlocking {
        withFixture { f ->
            val first = f.loginSuccessfully()
            assertTrue(f.repository.sync(first.id) is BitwardenRepository.SyncResult.Success)
            f.repository.forceLock(first.id)
            val before = f.row()
            val guard = f.reauthenticationContext(first.id)
            val candidate = f.environment(apiPath = "/candidate-api/")
            assertTrue(f.repository.isReauthenticationCurrent(guard))

            val result = f.login(candidate, guard)

            assertTrue("Publishing the new keys must not invalidate its own successful authentication",
                result is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(first.id, (result as BitwardenRepository.RepositoryLoginResult.Success).vault.id)
            val after = f.row()
            assertEquals(first.id, after.id)
            assertEquals(1, f.rows().size)
            assertEquals(f.userId, after.userId)
            assertEquals(candidate.fingerprint, f.resolve(after).fingerprint)
            assertEquals(f.endpoint + "/candidate-api/", f.resolve(after).api.toString())
            assertNotEquals(before.transportSecretRef, after.transportSecretRef)
            assertNotEquals(before.transportRevision, after.transportRevision)
            assertNull(f.security.getProtectedString(requireNotNull(before.transportSecretRef)))
            assertNotNull(f.security.getProtectedString(requireNotNull(after.transportSecretRef)))
            assertTrue(f.paths.contains("/candidate-api/accounts/profile"))
            assertTrue("Successful key publication must advance the vault generation",
                f.repository.getVaultSessionGeneration(first.id) > guard.vaultGeneration)
            assertFalse("The old submit authorization must remain obsolete after success",
                f.repository.isReauthenticationCurrent(guard))
            assertTrue(f.repository.isVaultUnlocked(first.id))
            assertFalse(after.isLocked)
            f.assertUserKey()
        }
    }

    @Test fun delayedCandidateProfileCannotReplaceAccountAfterForceLock(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val first = f.loginSuccessfully()
            val candidate = f.environment(apiPath = "/candidate-api/")
            val held = f.hold("/candidate-api/accounts/profile")
            val pending = async(Dispatchers.IO) { runCatching { f.login(candidate) } }
            try {
                held.awaitEntered()
                assertEquals(first, f.row())
                f.repository.forceLock(first.id)
                val locked = f.row()
                held.release.countDown()
                assertLoginRejected(withTimeout(30_000) { pending.await() }.getOrNull())
                assertEquals(locked, f.row())
                assertEquals(first.transportSecretRef, f.row().transportSecretRef)
                assertFalse(f.repository.isVaultUnlocked(first.id))
                assertTrue(f.row().isLocked)
                assertEquals(1, f.protectedReferences.size)
            } finally { held.release.countDown(); pending.cancelAndJoin() }
        } }
    }

    @Test fun changedEndpointMustVerifyTheKnownProfileBeforeReplacingProtectedConfiguration(): Unit = runBlocking {
        withFixture { f ->
            val first = f.loginSuccessfully()
            assertTrue(f.repository.sync(first.id) is BitwardenRepository.SyncResult.Success)
            val before = f.row()
            assertEquals(f.userId, before.userId)
            f.returnedProfileUserId = UUID.randomUUID().toString()
            assertLoginRejected(f.login(f.environment(apiPath = "/candidate-api/")))
            assertTrue("The rejection must come after a real candidate profile request",
                f.paths.contains("/candidate-api/accounts/profile"))
            assertEquals(before, f.row())
            assertEquals("Rejected candidate must never stage a replacement secret", 1, f.protectedReferences.size)
            assertTrue(f.repository.isVaultUnlocked(first.id))
            f.assertUserKey()

            f.returnedProfileUserId = null
            val accepted = f.login(f.environment(apiPath = "/candidate-api/"))
            assertTrue("Fresh candidate authentication with the correct profile must remain usable",
                accepted is BitwardenRepository.RepositoryLoginResult.Success)
            val after = f.row()
            assertEquals(before.id, after.id)
            assertEquals(before.userId, after.userId)
            assertEquals(f.endpoint + "/candidate-api/", f.resolve(after).api.toString())
            assertNotEquals(before.transportSecretRef, after.transportSecretRef)
            assertNotEquals(before.transportRevision, after.transportRevision)
            f.assertUserKey()
        }
    }

    @Test fun delayedRefreshCannotOverwriteAReopenedSessionWithTheSameOldToken(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val first = f.loginSuccessfully()
            f.dao.update(first.copy(accessTokenExpiresAt = 0L))
            val held = f.hold(TOKEN)
            val pending = async(Dispatchers.IO) { runCatching { f.repository.sync(first.id) } }
            try {
                held.awaitEntered()
                assertEquals("refresh_token", f.lastGrant.get())
                f.repository.forceLock(first.id)
                assertTrue(f.repository.unlock(first.id, PASSWORD) is BitwardenRepository.UnlockResult.Success)
                val reopened = f.row()
                f.assertUserKey()
                held.release.countDown()
                assertFalse("The old sync cannot succeed after token/key generation revocation",
                    withTimeout(30_000) { pending.await() }.getOrNull() is BitwardenRepository.SyncResult.Success)
                assertEquals(reopened, f.row())
                assertEquals(first.encryptedAccessToken, f.row().encryptedAccessToken)
                assertEquals(first.encryptedRefreshToken, f.row().encryptedRefreshToken)
                assertTrue(f.repository.isVaultUnlocked(first.id))
                assertFalse("Revoked refresh must not proceed to upload or pull", f.paths.contains(SYNC))
                f.assertUserKey()
            } finally { held.release.countDown(); pending.cancelAndJoin() }
        } }
    }

    @Test fun recoveredKeyWaitingAtPublicationCannotCrossGlobalLockGeneration(): Unit = runBlocking {
        withFixture { f -> coroutineScope {
            val first = f.loginSuccessfully()
            f.repository.forceLock(first.id)
            val before = f.row()
            val monitorEntered = CountDownLatch(1)
            val reset = CountDownLatch(1)
            val monitor = async(Dispatchers.IO) {
                synchronized(SessionManager) {
                    monitorEntered.countDown()
                    check(reset.await(30, TimeUnit.SECONDS)) { "Test publication barrier timed out" }
                    SessionManager.markLocked()
                    SessionManager.markUnlocked()
                }
            }
            assertTrue(monitorEntered.await(10, TimeUnit.SECONDS))
            val pending = async(Dispatchers.IO) { runCatching { f.repository.unlock(first.id, PASSWORD) } }
            try {
                // Observe the real recovered-key publisher blocked at the production monitor.
                // Merely observing that unlock started would not prove it captured the old generation.
                withTimeout(20_000) {
                    while (Thread.getAllStackTraces().none { (thread, frames) ->
                        thread.state == Thread.State.BLOCKED && frames.any {
                            it.className.startsWith(BitwardenRepository::class.java.name) &&
                                (it.className + "." + it.methodName).contains("publishRecoveredVault")
                        }
                    }) delay(10)
                }
                reset.countDown()
                withTimeout(10_000) { monitor.await() }
                assertFalse("An old recovered key cannot publish in the reopened main session",
                    withTimeout(20_000) { pending.await() }.getOrNull() is BitwardenRepository.UnlockResult.Success)
                assertEquals(before, f.row())
                assertTrue(f.row().isLocked)
                assertFalse(f.repository.isVaultUnlocked(first.id))
            } finally { reset.countDown(); monitor.cancelAndJoin(); pending.cancelAndJoin() }
        } }
    }

    private fun assertLoginRejected(result: BitwardenRepository.RepositoryLoginResult?) {
        assertFalse("Obsolete or unverifiable authentication must not publish success",
            result is BitwardenRepository.RepositoryLoginResult.Success)
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

    private class HeldResponse(val path: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitEntered() = assertTrue("Expected production request never reached the TLS server",
            entered.await(25, TimeUnit.SECONDS))
    }

    private inner class Fixture {
        val repository = BitwardenRepository(context)
        private val repositoryInstance = BitwardenRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private var originalRepository: BitwardenRepository? = null
        val database = PasswordDatabase.getDatabase(context)
        val dao = database.bitwardenVaultDao()
        val security = SecurityManager(context)
        val userId = UUID.randomUUID().toString()
        val email = "transport-auth-$userId@example.invalid"
        val paths = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val protectedReferences = CopyOnWriteArrayList<String>()
        val failNextStageReadback = AtomicBoolean(false)
        val failedReadbacks = AtomicInteger()
        val lastGrant = AtomicReference<String>()
        @Volatile var returnedProfileUserId: String? = null
        @Volatile var requireMfa = false
        private val hiddenReadback = AtomicReference<String?>()
        private val nextHeld = AtomicReference<HeldResponse?>()
        private val heldResponses = CopyOnWriteArrayList<HeldResponse>()
        private val server = MockWebServer()
        private val userKey = SymmetricCryptoKey(ByteArray(32) { 23 }, ByteArray(32) { 47 })
        private val tokenSequence = AtomicInteger()
        private val preferences: SharedPreferences = RecoverableBitwardenSettings.open(context)
        private val previousPrefs = listOf(ACTIVE, LAST_SYNC).associateWith { name ->
            preferences.contains(name) to preferences.getLong(name, 0L)
        }
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        private val originalMdk = mdkField.get(null) as? ByteArray
        private var started = false
        private lateinit var pem: String
        private lateinit var wrappedUserKey: String
        private lateinit var passwordHash: String
        lateinit var endpoint: String
        private var initialized = false
        private val protectedStore = BitwardenTransportSecretStore(
            read = { reference ->
                if (hiddenReadback.compareAndSet(reference, null)) {
                    failedReadbacks.incrementAndGet()
                    null
                } else security.getProtectedString(reference)
            },
            write = { reference, value ->
                protectedReferences += reference
                security.putProtectedString(reference, value)
                if (failNextStageReadback.compareAndSet(true, false)) hiddenReadback.set(reference)
            },
            remove = security::removeProtectedString,
        )

        suspend fun initialize() {
            check(BitwardenRepository.getInstance(context).getAllVaults().none {
                BitwardenRepository.getInstance(context).isVaultUnlocked(it.id)
            }) { "Run only in a dedicated synthetic Android user without unlocked accounts" }
            // Production consumers (including encrypted Send cleanup) share this singleton.
            // An isolated login repository otherwise leaves those consumers in a locked session.
            originalRepository = repositoryInstance.get(null) as BitwardenRepository
            repositoryInstance.set(null, repository)
            // Only this repository instance is injected. Storage still uses real encrypted preferences.
            BitwardenRepository::class.java.getDeclaredField("transportSecrets\$delegate").apply { isAccessible = true }
                .set(repository, lazyOf(protectedStore))
            instrumentation.runOnMainSync { SessionManager.markUnlocked() }
            mdkField.set(null, ByteArray(32) { 91 })
            initialized = true
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
                wrappedUserKey = BitwardenCrypto.encrypt(userKey.encKey + userKey.macKey, stretched)
            } finally { master.fill(0); stretched.clear() }
            server.dispatcher = object : HttpDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += path
                    requests += request
                    if (request.getHeader(HEADER_NAME) != HEADER_VALUE) return MockResponse().setResponseCode(403)
                    val response = when (path) {
                        "/identity/accounts/prelogin" -> ok("{\"kdf\":0,\"kdfIterations\":$ITERATIONS}")
                        TOKEN -> token(request)
                        PROFILE, "/candidate-api/accounts/profile" -> ok(profile().toString())
                        SYNC -> ok(buildJsonObject {
                            put("profile", profile())
                            put("ciphers", JsonArray(emptyList()))
                            put("folders", JsonArray(emptyList()))
                            put("sends", JsonArray(emptyList()))
                        }.toString())
                        else -> MockResponse().setResponseCode(404)
                    }
                    val candidate = nextHeld.get()
                    if (candidate != null && candidate.path == path && nextHeld.compareAndSet(candidate, null)) {
                        candidate.entered.countDown()
                        if (!candidate.release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    }
                    return response
                }
            }
        }

        private fun profile() = buildJsonObject {
            put("id", returnedProfileUserId ?: userId); put("email", email); put("securityStamp", "synthetic-transport-stamp-$userId")
        }

        private fun token(request: RecordedRequest): MockResponse {
            val form = request.body.clone().readUtf8().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            lastGrant.set(form["grant_type"])
            if (form["grant_type"] == "password" && (form["username"] != email || form["password"] != passwordHash)) {
                return MockResponse().setResponseCode(403)
            }
            if (form["grant_type"] == "refresh_token" && form["refresh_token"] != "synthetic-refresh-$userId") {
                return MockResponse().setResponseCode(403)
            }
            if (requireMfa && form["grant_type"] == "password" &&
                (form["twoFactorProvider"] != "0" || form["twoFactorToken"] != "123456")) {
                return MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"invalid_grant\",\"TwoFactorProviders\":[\"0\"],\"TwoFactorProviders2\":{\"0\":{}}}")
            }
            val serial = tokenSequence.incrementAndGet()
            return ok(buildJsonObject {
                put("access_token", "synthetic-access-$userId-$serial")
                put("refresh_token", if (form["grant_type"] == "refresh_token") "synthetic-rotated-$userId" else "synthetic-refresh-$userId")
                put("expires_in", 3600); put("Key", wrappedUserKey)
            }.toString())
        }

        fun environment(apiPath: String = "/api/"): BitwardenEnvironment = requireNotNull(
            BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
                BitwardenEnvironmentPreset.CUSTOM, baseUrl = endpoint,
                apiUrl = endpoint + apiPath, identityUrl = "$endpoint/identity/", webUrl = "$endpoint/web/",
                notificationsUrl = "$endpoint/notifications/", headers = listOf(BitwardenHeaderDraft(HEADER_NAME, HEADER_VALUE)),
                tls = BitwardenTlsConfig(caCertificatePem = pem),
            ), UUID.randomUUID().toString()).value)

        suspend fun login(
            environment: BitwardenEnvironment = environment(),
            reauthentication: BitwardenRepository.ReauthenticationContext? = null,
        ) = repository.login(endpoint, email, PASSWORD, environment = environment, reauthentication = reauthentication)

        fun reauthenticationContext(vaultId: Long) = BitwardenRepository.ReauthenticationContext(
            vaultId, repository.getVaultSessionGeneration(vaultId), SessionManager.lockGeneration.value,
        )

        suspend fun loginSuccessfully(): BitwardenVault {
            val result = login()
            assertTrue("Synthetic HTTPS login must publish the verified account",
                result is BitwardenRepository.RepositoryLoginResult.Success)
            return row()
        }

        suspend fun rows() = dao.getAllVaults().filter { it.email == email }
        suspend fun row(): BitwardenVault = rows().single()
        fun resolve(vault: BitwardenVault) = BitwardenVaultEnvironmentStore(protectedStore).resolve(vault)

        fun hold(path: String) = HeldResponse(path).also {
            check(nextHeld.compareAndSet(null, it))
            heldResponses += it
        }

        suspend fun assertUserKey() {
            val key = requireNotNull(repository.getCachedSymmetricKey(row().id))
            try { assertArrayEquals(userKey.encKey, key.encKey); assertArrayEquals(userKey.macKey, key.macKey) }
            finally { key.clear() }
        }

        fun resetMainSession() {
            instrumentation.runOnMainSync { SessionManager.markLocked(); SessionManager.markUnlocked() }
            mdkField.set(null, ByteArray(32) { 91 })
        }

        suspend fun close() {
            heldResponses.forEach { it.release.countDown() }
            try {
                if (initialized) rows().forEach {
                    check(it.email == email && it.serverUrl.trimEnd('/') == endpoint)
                    check(repository.logout(it.id)) { "Could not remove owned transport authentication fixture" }
                }
                protectedReferences.forEach { security.removeProtectedString(it) }
            } finally {
                try { if (started) server.shutdown() }
                finally {
                    userKey.clear()
                    if (initialized) {
                        repositoryInstance.set(null, originalRepository)
                        val edit = preferences.edit()
                        previousPrefs.forEach { (name, old) -> if (old.first) edit.putLong(name, old.second) else edit.remove(name) }
                        check(edit.commit())
                        instrumentation.runOnMainSync {
                            if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
                        }
                        mdkField.set(null, originalMdk)
                    }
                }
            }
        }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    /** Test-owned, self-signed localhost identity; no global trust or hostname verifier is changed. */
    private fun createTlsIdentity(): Pair<SSLContext, String> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name("CN=Monica Synthetic Transport Authentication")
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
        val password = "synthetic-server-key".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password); setKeyEntry("server", pair.private, password, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        password.fill('\u0000')
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(bytes, Base64.NO_WRAP)
            .chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        return tls to pem
    }

    private companion object {
        const val PASSWORD = "Synthetic transport authentication 42!"
        const val ITERATIONS = 600_000
        const val HEADER_NAME = "X-Monica-Synthetic-Proxy"
        const val HEADER_VALUE = "synthetic-transport-proxy-secret"
        const val TOKEN = "/identity/connect/token"
        const val PROFILE = "/api/accounts/profile"
        const val SYNC = "/api/sync"
        const val ACTIVE = "active_vault_id"
        const val LAST_SYNC = "last_sync_time"
    }
}

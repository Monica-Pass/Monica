package takagi.ru.monica.bitwarden

import android.os.Looper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverApi
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverChallenge
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverCredential
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverDownload
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverTokenReply
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.PreLoginRequest
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverException
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverFailure
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverTarget
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Actual Android TLS sockets, real production managers, normal StrictMode; no database/session fixture. */
@RunWith(AndroidJUnit4::class)
class ToolchainBitwardenTransportRetirementInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun requireOwnedSyntheticProcess() {
        check(Process.myUid() / 100_000 == 11)
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("toolchainBitwardenRetirement") == "yes")
        val expected = checkNotNull(args.getString("toolchainTargetApkSha256"))
        check(expected.matches(Regex("[0-9a-f]{64}")) && expected == sha(File(context.applicationInfo.sourceDir)))
    }

    @Test fun retireEnvironmentOnMainRevokesCapturedApiAndEventuallyClosesIdleTlsPool(): Unit = retirementTest { fixture ->
        val environment = fixture.environment()
        val manager = fixture.manager()
        val api = manager.getIdentityApi(environment)
        assertTrue(api.preLogin(PreLoginRequest(EMAIL)).isSuccessful)
        val old = fixture.managerClients(manager).single()
        awaitIdle(old.connectionPool)
        val capturedCall = old.newCall(fixture.request("/old-attempt"))

        onMain {
            manager.retireEnvironment(environment)
            assertFalse(manager.isEnvironmentActive(environment))
            assertTrue(old.dispatcher.executorService.isShutdown)
        }
        rejectIo { capturedCall.execute().close() }
        rejectIo { api.preLogin(PreLoginRequest(EMAIL)); Unit }
        rejectRetiredAttempt { manager.getIdentityApi(environment) }
        rejectRetiredAttempt { manager.getVaultApi(environment) }
        assertEquals(1, fixture.server.requestCount)
        awaitEmpty(old.connectionPool)
    }

    @Test fun retireVaultOnMainRejectsOldCallsAndPreservesOtherAndReplacementPools(): Unit = retirementTest { fixture ->
        val environment = fixture.environment()
        val manager = fixture.manager(environment)
        val firstVault = vault(1)
        val secondVault = vault(2)
        val old = fixture.track(manager.getOkHttpClient(firstVault))
        val other = fixture.track(manager.getOkHttpClient(secondVault))
        assertNotSame(old.connectionPool, other.connectionPool)
        fixture.warm(old, "/first")
        fixture.warm(other, "/other")
        awaitIdle(old.connectionPool)
        awaitIdle(other.connectionPool)
        val capturedCall = old.newCall(fixture.request("/old-vault"))

        onMain {
            manager.retireVault(firstVault.id)
            assertTrue(old.dispatcher.executorService.isShutdown)
            assertFalse(other.dispatcher.executorService.isShutdown)
        }
        rejectIo { capturedCall.execute().close() }
        val replacement = fixture.track(manager.getOkHttpClient(firstVault))
        assertNotSame(old, replacement)
        assertNotSame(old.connectionPool, replacement.connectionPool)
        assertNotSame(other.connectionPool, replacement.connectionPool)
        fixture.warm(other, "/other-after")
        fixture.warm(replacement, "/replacement")
        awaitEmpty(old.connectionPool)
        awaitIdle(other.connectionPool)
        awaitIdle(replacement.connectionPool)
        assertEquals(4, fixture.server.requestCount)
    }

    @Test fun clearCacheOnMainRevokesEveryCapturedTransportAndAllowsOnlyNewAttempts(): Unit = retirementTest { fixture ->
        val environment = fixture.environment()
        val manager = fixture.manager(environment)
        val api = manager.getIdentityApi(environment)
        assertTrue(api.preLogin(PreLoginRequest(EMAIL)).isSuccessful)
        val vaultClient = fixture.track(manager.getOkHttpClient(vault(3)))
        fixture.warm(vaultClient, "/vault-before-clear")
        val oldClients = fixture.managerClients(manager)
        assertEquals(2, oldClients.size)
        assertNotSame(oldClients[0].connectionPool, oldClients[1].connectionPool)
        oldClients.forEach { awaitIdle(it.connectionPool) }
        val oldCalls = oldClients.mapIndexed { index, client -> client.newCall(fixture.request("/old-clear-$index")) }

        onMain {
            manager.clearCache()
            assertFalse(manager.isEnvironmentActive(environment))
            oldClients.forEach { assertTrue(it.dispatcher.executorService.isShutdown) }
        }
        oldCalls.forEach { call -> rejectIo { call.execute().close() } }
        rejectIo { api.preLogin(PreLoginRequest(EMAIL)); Unit }
        rejectRetiredAttempt { manager.getIdentityApi(environment) }
        val nextEnvironment = fixture.environment()
        assertNotEquals(environment.revision, nextEnvironment.revision)
        assertTrue(manager.getIdentityApi(nextEnvironment).preLogin(PreLoginRequest(EMAIL)).isSuccessful)
        val replacement = fixture.managerClients(manager).single()
        oldClients.forEach { assertNotSame(it.connectionPool, replacement.connectionPool); awaitEmpty(it.connectionPool) }
        awaitIdle(replacement.connectionPool)
        assertEquals(3, fixture.server.requestCount)
    }

    @Test fun retiringHeldTlsCallOnMainCancelsImmediatelyAndNeverPublishesItsResponse(): Unit = retirementTest { fixture ->
        coroutineScope {
            val environment = fixture.environment()
            val manager = fixture.manager(environment)
            val firstVault = vault(4)
            val old = fixture.track(manager.getOkHttpClient(firstVault))
            val other = fixture.track(manager.getOkHttpClient(vault(5)))
            fixture.warm(other, "/held-survivor-before")
            val call = old.newCall(fixture.request("/held"))
            val published = AtomicBoolean(false)
            val request = async(Dispatchers.IO) {
                try {
                    call.execute().use { response -> response.body!!.string(); published.set(true) }
                    null
                } catch (error: IOException) { error }
            }
            try {
                assertTrue("Owned TLS request did not reach its holding point", fixture.heldEntered.await(10, TimeUnit.SECONDS))
                onMain {
                    manager.retireVault(firstVault.id)
                    assertTrue("Cancellation must be visible before retirement returns", call.isCanceled())
                    assertTrue(old.dispatcher.executorService.isShutdown)
                }
                assertNotNull(withTimeout(10_000) { request.await() })
                assertFalse(published.get())
                rejectIo { old.newCall(fixture.request("/late-old-held")).execute().close() }
                val replacement = fixture.track(manager.getOkHttpClient(firstVault))
                assertNotSame(old.connectionPool, replacement.connectionPool)
                fixture.warm(other, "/held-survivor-after")
                fixture.warm(replacement, "/held-replacement")
                fixture.heldRelease.countDown()
                awaitEmpty(old.connectionPool)
                awaitIdle(other.connectionPool)
                awaitIdle(replacement.connectionPool)
                assertFalse(published.get())
                assertEquals(4, fixture.server.requestCount)
            } finally {
                fixture.heldRelease.countDown()
                call.cancel()
                request.cancelAndJoin()
            }
        }
    }

    @Test fun closingSendReceiverOnMainRejectsFurtherUseAndPreservesAnotherReceiver(): Unit = retirementTest { fixture ->
        val first = fixture.receiver()
        val other = fixture.receiver()
        val target = BitwardenSendReceiverTarget(fixture.url("/"), fixture.url("/api/"), fixture.url("/identity/"))
        val download = BitwardenSendReceiverDownload("owned-file", fixture.url("/blob"))
        listOf(first, other).forEach { receiver ->
            assertPasswordChallenge(receiver.token(target, SEND_ID, BitwardenSendReceiverCredential.none()))
            val bytes = ByteArrayOutputStream()
            receiver.download(download, BLOB.size.toLong(), bytes, {}, { _, _ -> })
            assertArrayEquals(BLOB, bytes.toByteArray())
        }
        val firstClients = fixture.receiverClients(first)
        val otherClients = fixture.receiverClients(other)
        assertNotSame(firstClients[0].connectionPool, firstClients[1].connectionPool)
        firstClients.forEach { a -> otherClients.forEach { b -> assertNotSame(a.connectionPool, b.connectionPool) } }
        (firstClients + otherClients).forEach { awaitIdle(it.connectionPool) }
        val requestsBeforeClose = fixture.server.requestCount

        onMain { first.close() }
        rejectClosed { first.token(target, SEND_ID, BitwardenSendReceiverCredential.none()); Unit }
        val deniedOutput = ByteArrayOutputStream()
        var progressCalls = 0
        rejectClosed { first.download(download, BLOB.size.toLong(), deniedOutput, {}, { _, _ -> progressCalls++ }) }
        assertEquals(0, deniedOutput.size())
        assertEquals(0, progressCalls)
        assertEquals(requestsBeforeClose, fixture.server.requestCount)
        firstClients.forEach { awaitEmpty(it.connectionPool) }

        assertPasswordChallenge(other.token(target, SEND_ID, BitwardenSendReceiverCredential.none()))
        val survivingOutput = ByteArrayOutputStream()
        other.download(download, BLOB.size.toLong(), survivingOutput, {}, { _, _ -> })
        assertArrayEquals(BLOB, survivingOutput.toByteArray())
        otherClients.forEach { awaitIdle(it.connectionPool) }
        assertEquals(6, fixture.server.requestCount)
    }

    /** Captures the original exception for JUnit without killing the main Looper or changing StrictMode. */
    private fun onMain(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try { assertSame(Looper.getMainLooper(), Looper.myLooper()); action() }
            catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }

    private fun retirementTest(block: suspend (TlsFixture) -> Unit): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = TlsFixture()
            var originalFailure: Throwable? = null
            try { block(fixture) }
            catch (error: Throwable) { originalFailure = error; throw error }
            finally {
                try { fixture.close() }
                catch (cleanup: Throwable) { if (originalFailure != null) originalFailure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private suspend fun awaitIdle(pool: ConnectionPool) = withTimeout(10_000) {
        while (pool.idleConnectionCount() == 0) delay(20)
    }

    private suspend fun awaitEmpty(pool: ConnectionPool) = withTimeout(10_000) {
        while (pool.connectionCount() != 0) delay(20)
    }

    private suspend fun rejectIo(action: suspend () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Retired transport issued a request")
    }

    private fun rejectRetiredAttempt(action: () -> Any) {
        try { action() } catch (_: IllegalStateException) { return }
        fail("Retired attempt obtained a new API")
    }

    private suspend fun rejectClosed(action: suspend () -> Unit) {
        try { action() } catch (error: BitwardenSendReceiverException) {
            assertEquals(BitwardenSendReceiverFailure.CLOSED, error.code)
            return
        }
        fail("Closed Send receiver accepted an operation")
    }

    private fun assertPasswordChallenge(reply: BitwardenSendReceiverTokenReply) {
        assertTrue(reply is BitwardenSendReceiverTokenReply.Challenge)
        assertEquals(BitwardenSendReceiverChallenge.PASSWORD, (reply as BitwardenSendReceiverTokenReply.Challenge).challenge)
    }

    private fun vault(id: Long) = BitwardenVault(id = id, email = "transport-$id@example.invalid")

    private class TlsFixture {
        private val identity = tlsIdentity()
        val server = MockWebServer()
        val heldEntered = CountDownLatch(1)
        val heldRelease = CountDownLatch(1)
        private val managers = mutableListOf<BitwardenApiManager>()
        private val receivers = mutableListOf<BitwardenSendReceiverApi>()
        private val clients = linkedSetOf<OkHttpClient>()

        init {
            server.useHttps(identity.context.socketFactory, false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/identity/accounts/prelogin" -> json("""{"kdf":0,"kdfIterations":600000}""")
                    "/identity/connect/token" -> json("""{"error":"invalid_request","send_access_error_type":"password_hash_b64_required"}""", 400)
                    "/blob" -> MockResponse().setBody(Buffer().write(BLOB))
                    "/held" -> {
                        heldEntered.countDown()
                        if (heldRelease.await(30, TimeUnit.SECONDS)) json("""{"held":"released"}""")
                        else MockResponse().setResponseCode(504)
                    }
                    else -> json("""{"transport":"owned-localhost"}""")
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun url(path: String): HttpUrl = server.url(path).newBuilder().host("localhost").build()
        fun request(path: String): Request = Request.Builder().url(url(path)).build()
        fun environment(): BitwardenEnvironment = requireNotNull(BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, baseUrl = url("/").toString(),
                apiUrl = url("/api/").toString(), identityUrl = url("/identity/").toString(),
                tls = BitwardenTlsConfig(caCertificatePem = identity.pem)), UUID.randomUUID().toString()).value)
        fun manager(environment: BitwardenEnvironment? = null): BitwardenApiManager =
            (if (environment == null) BitwardenApiManager() else BitwardenApiManager(environmentForVault = { environment }))
                .also { managers += it }
        fun receiver(): BitwardenSendReceiverApi = BitwardenSendReceiverApi.withTrustForTests(identity.trust).also {
            receivers += it
            receiverClients(it)
        }
        fun track(client: OkHttpClient): OkHttpClient = client.also { clients += it }
        fun warm(client: OkHttpClient, path: String) {
            client.newCall(request(path)).execute().use { response ->
                assertEquals(200, response.code)
                assertNotNull(response.handshake)
                assertTrue(response.body!!.string().isNotEmpty())
            }
        }

        // Observe only owned pools so negative runs can clean up even if retire threw after removing a cache entry.
        fun managerClients(manager: BitwardenApiManager): List<OkHttpClient> = synchronized(manager) {
            val entries = BitwardenApiManager::class.java.getDeclaredField("clients").apply { isAccessible = true }
                .get(manager) as Map<*, *>
            entries.values.map { entry ->
                val value = requireNotNull(entry)
                track(value.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(value) as OkHttpClient)
            }
        }
        fun receiverClients(receiver: BitwardenSendReceiverApi): List<OkHttpClient> =
            listOf("apiClient", "storageClient").map { name ->
                track(BitwardenSendReceiverApi::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(receiver) as OkHttpClient)
            }

        fun close() {
            check(Looper.myLooper() != Looper.getMainLooper())
            heldRelease.countDown()
            var failure: Throwable? = null
            fun clean(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            managers.forEach { manager -> clean { managerClients(manager); manager.clearCache() } }
            receivers.forEach { receiver -> clean { receiver.close() } }
            clients.forEach { client ->
                clean { client.dispatcher.cancelAll() }
                clean { client.connectionPool.evictAll() }
                clean { client.dispatcher.executorService.shutdown() }
            }
            clean { server.shutdown() }
            failure?.let { throw it }
        }
    }

    private data class TlsIdentity(val context: SSLContext, val trust: X509TrustManager, val pem: String)

    private companion object {
        const val EMAIL = "retirement@example.invalid"
        const val SEND_ID = "AAECAwQFBgcICQoLDA0ODw"
        val BLOB = ByteArray(19) { (it * 7).toByte() }

        fun json(value: String, status: Int = 200): MockResponse = MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(value)

        /** Same low-level ASN.1 fixture technique already used by the real authenticated Repository tests. */
        fun tlsIdentity(): TlsIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val subject = X500Name("CN=Monica Synthetic TLS Retirement")
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
            val encoded = sequence(tbs, algorithm, DERBitString(signature)).encoded
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            certificate.verify(pair.public)
            val password = "synthetic-retirement-only".toCharArray()
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry("owned-server", pair.private, password, arrayOf(certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            password.fill('\u0000')
            val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null); setCertificateEntry("owned-localhost-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
            return TlsIdentity(context, trust, pem)
        }

        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

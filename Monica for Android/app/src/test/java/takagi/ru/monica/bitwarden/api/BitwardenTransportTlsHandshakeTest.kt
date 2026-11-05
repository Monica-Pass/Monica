package takagi.ru.monica.bitwarden.api

import android.app.Application
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Real loopback handshakes; Robolectric supplies only the factory's Android Base64 implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BitwardenTransportTlsHandshakeTest {
    @Test fun customCaAugmentsTheDefaultTrustBranchWithActualRequestsToBothServers() = withSystemTrust {
        TlsServer(systemServer).use { system -> TlsServer(customServer).use { custom ->
            val environment = environment(system.url("/Vault/"), api = custom.url("/Api/"))
            withClient(BitwardenApiFactory.createAccountClient(environment)) { client ->
                val download = byteArrayOf(0, 1, 2, 127, -1, 42)
                val upload = byteArrayOf(-1, 0, 21, 65, 66, 67)
                system.server.enqueue(MockResponse().setBody(Buffer().write(download)))
                custom.server.enqueue(MockResponse().setBody("custom-ca-response"))
                client.newCall(Request.Builder().url(system.url("/Vault/download")).build()).execute().use {
                    assertArrayEquals(download, it.body!!.bytes())
                    assertArrayEquals(systemServer.certificate.encoded, it.handshake!!.peerCertificates.first().encoded)
                }
                client.newCall(Request.Builder().url(custom.url("/Api/upload")).post(upload.toRequestBody()).build()).execute().use {
                    assertEquals("custom-ca-response", it.body!!.string())
                    assertArrayEquals(customServer.certificate.encoded, it.handshake!!.peerCertificates.first().encoded)
                }
                val first = recorded(system)
                val second = recorded(custom)
                assertEquals("/Vault/download", first.path)
                assertEquals("/Api/upload", second.path)
                assertArrayEquals(upload, second.body.readByteArray())
                assertEquals(PROXY_SECRET, first.getHeader(PROXY_HEADER))
                assertEquals(PROXY_SECRET, second.getHeader(PROXY_HEADER))
                assertNotNull(first.handshake)
                assertNotNull(second.handshake)
                assertTrue(system.sockets.completed.await(2, TimeUnit.SECONDS))
                assertTrue(custom.sockets.completed.await(2, TimeUnit.SECONDS))
            }
        } }
    }

    @Test fun allowedMtlsOriginReceivesTheSyntheticClientCertificateAndExactHttpBytes() = withSystemTrust {
        TlsServer(customServer, ClientAuth.REQUIRE).use { server ->
            val environment = environment(server.url("/Vault/"), mtls = true)
            withClient(BitwardenApiFactory.createAccountClient(environment)) { client ->
                val bytes = byteArrayOf(0, -1, 3, 4, 10, 13, 65)
                server.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                client.newCall(Request.Builder().url(server.url("/Vault/api/upload"))
                    .header("Authorization", "Bearer synthetic-account-token").post(bytes.toRequestBody()).build()).execute().use {
                    assertEquals(200, it.code)
                    assertArrayEquals(bytes, it.body!!.bytes())
                    assertArrayEquals(customServer.certificate.encoded, it.handshake!!.peerCertificates.first().encoded)
                }
                val request = recorded(server)
                assertEquals("POST", request.method)
                assertArrayEquals(bytes, request.body.readByteArray())
                assertEquals(PROXY_SECRET, request.getHeader(PROXY_HEADER))
                assertEquals(environment.base.toString(), request.getHeader("Referer"))
                assertEquals("Bearer synthetic-account-token", request.getHeader("Authorization"))
                assertArrayEquals(clientIdentity.certificate.encoded, request.handshake!!.peerCertificates.first().encoded)
                assertTrue(server.sockets.completed.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun outOfScopeOriginWebAndLookalikePathsAreRejectedBeforeServerTlsCreation() = withSystemTrust {
        TlsServer(customServer, ClientAuth.REQUIRE).use { account -> TlsServer(customServer, ClientAuth.REQUIRE).use { other ->
            val environment = environment(account.url("/Vault/"), web = other.url("/Web/"), mtls = true)
            withClient(BitwardenApiFactory.createAccountClient(environment)) { client ->
                // A regressed scope gate gets a fast real response, not a timeout mistaken for rejection.
                repeat(3) { account.server.enqueue(MockResponse().setBody("unexpected")); other.server.enqueue(MockResponse().setBody("unexpected")) }
                listOf(other.url("/Web/capture"), account.url("/Vault2/capture"), account.url("/vault/capture")).forEach { url ->
                    val failure = expectIoFailure { client.newCall(Request.Builder().url(url).build()).execute().close() }
                    assertEquals("Bitwarden transport destination rejected", failure.message)
                }
                assertNoTlsConnection(account)
                assertNoTlsConnection(other)
            }
        } }
    }

    @Test fun redirectsDoNotSendAnotherHttpRequestOrHandshakeTheForeignTarget() = withSystemTrust {
        TlsServer(customServer, ClientAuth.REQUIRE).use { account -> TlsServer(customServer, ClientAuth.REQUIRE).use { other ->
            withClient(BitwardenApiFactory.createAccountClient(environment(account.url("/Vault/"), mtls = true))) { client ->
                other.server.enqueue(MockResponse().setBody("unexpected redirected content"))
                listOf(other.url("/capture"), account.url("/Outside/capture")).forEachIndexed { index, target ->
                    account.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target).setBody("redirect-$index"))
                    client.newCall(Request.Builder().url(account.url("/Vault/redirect-$index")).build()).execute().use {
                        assertEquals(302, it.code)
                        assertEquals("redirect-$index", it.body!!.string())
                    }
                    val request = recorded(account)
                    assertEquals("/Vault/redirect-$index", request.path)
                    assertArrayEquals(clientIdentity.certificate.encoded, request.handshake!!.peerCertificates.first().encoded)
                }
                assertEquals(2, account.server.requestCount)
                assertNoTlsConnection(other)
            }
        } }
    }

    @Test fun foreignSignedClientUsesSeparatePoolAndSendsNoAccountCertificateOrHeaders() = withSystemTrust {
        TlsServer(customServer, ClientAuth.REQUIRE).use { account -> TlsServer(systemServer, ClientAuth.REQUEST).use { storage ->
            val environment = environment(account.url("/Vault/"), mtls = true)
            withClient(BitwardenApiFactory.createAccountClient(environment)) { accountClient ->
                withClient(BitwardenApiFactory.createForeignTransferClient()) { foreignClient ->
                    account.server.enqueue(MockResponse().setBody("account"))
                    accountClient.newCall(Request.Builder().url(account.url("/Vault/check")).build()).execute().use {
                        assertEquals("account", it.body!!.string())
                    }
                    assertArrayEquals(clientIdentity.certificate.encoded, recorded(account).handshake!!.peerCertificates.first().encoded)
                    val remote = storage.url("/blob?sig=synthetic%2Fcapability")
                    val target = requireNotNull(BitwardenTransportPolicy.validateTransferUrl(environment, remote.toString()).value)
                    assertFalse(target.useAccountTransport)
                    val payload = byteArrayOf(10, 20, -1, 0, 127)
                    storage.server.enqueue(MockResponse().setBody(Buffer().write(payload)))
                    foreignClient.newCall(Request.Builder().url(target.url).build()).execute().use {
                        assertArrayEquals(payload, it.body!!.bytes())
                        assertArrayEquals(systemServer.certificate.encoded, it.handshake!!.peerCertificates.first().encoded)
                    }
                    val request = recorded(storage)
                    assertEquals("/blob?sig=synthetic%2Fcapability", request.path)
                    assertTrue(request.handshake!!.peerCertificates.isEmpty())
                    listOf(PROXY_HEADER, "Authorization", "Proxy-Authorization", "Referer", "Cookie").forEach { assertNull(it, request.getHeader(it)) }
                    assertNotSame(accountClient.connectionPool, foreignClient.connectionPool)
                    assertTrue(storage.sockets.completed.await(2, TimeUnit.SECONDS))
                }
            }
        } }
    }

    @Test fun foreignClientCannotReuseCustomCaEvenAfterTheAccountClientTrustedThatServer() = withSystemTrust {
        TlsServer(customServer).use { server ->
            withClient(BitwardenApiFactory.createAccountClient(environment(server.url("/Vault/")))) { accountClient ->
                server.server.enqueue(MockResponse().setBody("account trusts custom CA"))
                accountClient.newCall(Request.Builder().url(server.url("/Vault/first")).build()).execute().use {
                    assertEquals("account trusts custom CA", it.body!!.string())
                }
                assertEquals("/Vault/first", recorded(server).path)
                val acceptedBefore = server.sockets.created.get()
                withClient(BitwardenApiFactory.createForeignTransferClient()) { foreign ->
                    server.server.enqueue(MockResponse().setBody("must not be downloaded"))
                    val failure = expectIoFailure { foreign.newCall(Request.Builder().url(server.url("/blob?sig=synthetic")).build()).execute().close() }
                    assertTrue(causes(failure).any { it is SSLHandshakeException })
                    assertTrue(server.sockets.created.get() > acceptedBefore)
                    assertNoSuccessfulHttp(server)
                    assertNotSame(accountClient.connectionPool, foreign.connectionPool)
                }
            }
        }
    }

    @Test fun trustedCertificateForAnotherHostnameStillCannotDeliverHttp() = withSystemTrust {
        TlsServer(wrongHostnameServer).use { server ->
            withClient(BitwardenApiFactory.createAccountClient(environment(server.url("/Vault/")))) { client ->
                server.server.enqueue(MockResponse().setBody("hostname bypass would expose this"))
                val failure = expectIoFailure { client.newCall(Request.Builder().url(server.url("/Vault/secret")).build()).execute().close() }
                assertTrue(causes(failure).any { it is SSLPeerUnverifiedException })
                assertTrue(server.sockets.created.get() > 0)
                assertTrue(server.sockets.completed.await(2, TimeUnit.SECONDS))
                assertNoSuccessfulHttp(server)
            }
        }
    }

    private fun environment(base: HttpUrl, api: HttpUrl? = null, web: HttpUrl? = null, mtls: Boolean = false): BitwardenEnvironment {
        val tls = BitwardenTlsConfig(caCertificatePem = customCa.pem, mtlsEnabled = mtls,
            clientCertPkcs12Base64 = if (mtls) pkcs12(clientIdentity, customCa) else null,
            clientCertPassword = if (mtls) KEY_PASSWORD else null)
        val result = BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            baseUrl = base.toString(), apiUrl = api?.toString(), webUrl = web?.toString(),
            headers = listOf(BitwardenHeaderDraft(PROXY_HEADER, PROXY_SECRET)), tls = tls), UUID.randomUUID().toString())
        assertTrue(result.errors.toString(), result.isValid)
        return requireNotNull(result.value)
    }

    private fun recorded(server: TlsServer): RecordedRequest = requireNotNull(server.server.takeRequest(3, TimeUnit.SECONDS)).also {
        assertNull(it.failure)
        assertNotNull(it.method)
        assertNotNull(it.handshake)
    }

    private fun assertNoTlsConnection(server: TlsServer) {
        assertFalse("Server accepted a TLS connection", server.sockets.accepted.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, server.sockets.created.get())
        assertEquals(0, server.server.requestCount)
    }

    private fun assertNoSuccessfulHttp(server: TlsServer) {
        // MockWebServer may record a failed empty read after TLS/hostname rejection.
        while (true) {
            val request = server.server.takeRequest(300, TimeUnit.MILLISECONDS) ?: return
            assertNull("HTTP reached a rejected peer", request.method)
            assertNotNull(request.failure)
        }
    }

    private fun expectIoFailure(action: () -> Unit): IOException {
        try { action() } catch (failure: IOException) { return failure }
        throw AssertionError("Request unexpectedly succeeded")
    }

    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.take(12).toList()

    private inline fun <T> withClient(client: OkHttpClient, block: (OkHttpClient) -> T): T = try { block(client) } finally {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Only this test JVM sees these properties; no host/Android trust store is modified. */
    private fun <T> withSystemTrust(block: () -> T): T = synchronized(systemTrustLock) {
        val properties = listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword")
        val previous = properties.associateWith { System.getProperty(it) }
        val file = File.createTempFile("monica-synthetic-system-trust-", ".jks")
        try {
            val store = KeyStore.getInstance("JKS").apply { load(null); setCertificateEntry("system-fixture", systemCa.certificate) }
            file.outputStream().use { store.store(it, KEY_PASSWORD.toCharArray()) }
            System.setProperty(properties[0], file.absolutePath)
            System.setProperty(properties[1], "JKS")
            System.setProperty(properties[2], KEY_PASSWORD)
            block()
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
            file.delete()
        }
    }

    private enum class ClientAuth { NONE, REQUEST, REQUIRE }

    private class TlsServer(identity: CertificateIdentity, clientAuth: ClientAuth = ClientAuth.NONE) : Closeable {
        val server = MockWebServer()
        val sockets: CountingSslSocketFactory

        init {
            val keyStore = KeyStore.getInstance("JKS").apply {
                load(null)
                setKeyEntry("server", identity.keyPair.private, KEY_PASSWORD.toCharArray(),
                    arrayOf<Certificate>(identity.certificate, requireNotNull(identity.issuer).certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, KEY_PASSWORD.toCharArray()) }
            val clientTrust = KeyStore.getInstance("JKS").apply { load(null); setCertificateEntry("client-ca", customCa.certificate) }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(clientTrust) }
            val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trustManagers.trustManagers, SecureRandom()) }
            sockets = CountingSslSocketFactory(context.socketFactory)
            server.useHttps(sockets, false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            when (clientAuth) { ClientAuth.REQUEST -> server.requestClientAuth(); ClientAuth.REQUIRE -> server.requireClientAuth(); else -> Unit }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun url(path: String): HttpUrl = server.url(path).newBuilder().host("localhost").build()
        override fun close() { server.shutdown() }
    }

    /** MockWebServer wraps each accepted TCP socket here immediately before startHandshake(). */
    private class CountingSslSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        val created = AtomicInteger()
        val accepted = CountDownLatch(1)
        val completed = CountDownLatch(1)
        private fun track(socket: Socket): Socket {
            created.incrementAndGet()
            accepted.countDown()
            (socket as SSLSocket).addHandshakeCompletedListener { completed.countDown() }
            return socket
        }
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket = track(delegate.createSocket())
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket = track(delegate.createSocket(socket, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = track(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = track(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = track(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = track(delegate.createSocket(address, port, localAddress, localPort))
    }

    private class CertificateIdentity(val keyPair: KeyPair, val certificate: X509Certificate, val pem: String, val issuer: CertificateIdentity?)

    companion object {
        private const val PROXY_HEADER = "CF-Access-Client-Secret"
        private const val PROXY_SECRET = "synthetic-proxy-credential"
        private const val KEY_PASSWORD = "synthetic-pkcs12-password"
        private val systemTrustLock = Any()
        private val serial = AtomicInteger(1)
        private val systemCa by lazy { certificate("Monica Synthetic System CA", ca = true) }
        private val customCa by lazy { certificate("Monica Synthetic Custom CA", ca = true) }
        private val systemServer by lazy { certificate("localhost", issuer = systemCa, dnsNames = listOf("localhost")) }
        private val customServer by lazy { certificate("localhost", issuer = customCa, dnsNames = listOf("localhost")) }
        private val clientIdentity by lazy { certificate("Monica Synthetic Client", issuer = customCa) }
        private val wrongHostnameServer by lazy { certificate("wrong.example.test", issuer = customCa, dnsNames = listOf("wrong.example.test")) }

        private fun sequence(vararg values: ASN1Encodable) = DERSequence(values)
        private fun extension(oid: org.bouncycastle.asn1.ASN1ObjectIdentifier, critical: Boolean, value: ASN1Encodable) =
            Extension(oid, critical, DEROctetString(value.toASN1Primitive().encoded))

        private fun certificate(name: String, ca: Boolean = false, issuer: CertificateIdentity? = null, dnsNames: List<String> = emptyList()): CertificateIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val subject = X500Name("CN=$name")
            val issuerName = issuer?.certificate?.subjectX500Principal?.encoded?.let { X500Name.getInstance(it) } ?: subject
            val extensions = mutableListOf(extension(Extension.basicConstraints, true, BasicConstraints(ca)),
                extension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment)))
            if (!ca) extensions += extension(Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)))
            if (dnsNames.isNotEmpty()) extensions += extension(Extension.subjectAlternativeName, false,
                GeneralNames(dnsNames.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()))
            val tbs = sequence(DERTaggedObject(true, 0, ASN1Integer(2)), ASN1Integer(serial.getAndIncrement().toLong()), algorithm, issuerName,
                sequence(Time(Date(1_577_836_800_000L)), Time(Date(4_102_444_800_000L))), subject,
                SubjectPublicKeyInfo.getInstance(keyPair.public.encoded), DERTaggedObject(true, 3, Extensions(extensions.toTypedArray())))
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(issuer?.keyPair?.private ?: keyPair.private); update(tbs.encoded) }.sign()
            val encoded = sequence(tbs, algorithm, DERBitString(signature)).encoded
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            certificate.verify(issuer?.keyPair?.public ?: keyPair.public)
            val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
            return CertificateIdentity(keyPair, certificate, pem, issuer)
        }

        private fun pkcs12(identity: CertificateIdentity, issuer: CertificateIdentity): String {
            val password = KEY_PASSWORD.toCharArray()
            try {
                val store = KeyStore.getInstance("PKCS12").apply {
                    load(null, password)
                    setKeyEntry("client", identity.keyPair.private, password, arrayOf<Certificate>(identity.certificate, issuer.certificate))
                }
                val bytes = ByteArrayOutputStream().use { output -> store.store(output, password); output.toByteArray() }
                return try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
            } finally { password.fill('\u0000') }
        }
    }
}

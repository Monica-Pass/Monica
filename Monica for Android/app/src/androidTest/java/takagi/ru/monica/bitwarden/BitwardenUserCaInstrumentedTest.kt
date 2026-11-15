package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.*

/** Real Android user CA store and loopback HTTPS; the host installs only this isolated user's CA.
 * Run prepareUserCaFixture first, then install its public root in that user's Android trust store.
 * Run removedUserCaIsRejected only after removing that same root. No user vault is involved.
 */
@RunWith(AndroidJUnit4::class)
class BitwardenUserCaInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.filesDir, "bitwarden-user-ca-328")

    @Test fun prepareUserCaFixture() {
        check(!directory.exists()) { "Do not overwrite a previous fixture" }
        check(directory.mkdirs())
        val root = certificate("Monica isolated user CA 328", ca = true)
        File(directory, "user-ca.der").writeBytes(root.cert.encoded)
        val md5 = MessageDigest.getInstance("MD5").digest(root.cert.subjectX500Principal.encoded)
        val hash = (0..3).fold(0L) { value, i -> value or ((md5[i].toLong() and 255) shl (8 * i)) }
        File(directory, "user-ca-name.txt").writeText("%08x.0".format(hash))
        save("first", certificate("localhost", root, hosts = listOf("localhost")), root)
        save("renewed", certificate("localhost", root, hosts = listOf("localhost")), root)
        save("official", certificate("vault.bitwarden.com", root, hosts = listOf("vault.bitwarden.com")), root)
        save("coalescing", certificate("self.example.test", root, hosts = listOf("self.example.test", "vault.bitwarden.com")), root)
        save("suffix", certificate("bitwarden.com.example.test", root, hosts = listOf("bitwarden.com.example.test")), root)
        save("wrong", certificate("other.example.test", root, hosts = listOf("other.example.test")), root)
        save("expired", certificate("localhost", root, hosts = listOf("localhost"), expired = true), root)
        val unknown = certificate("Monica uninstalled CA 328", ca = true)
        save("unknown", certificate("localhost", unknown, hosts = listOf("localhost")), unknown)
    }

    @Test fun installedUserCaWorksWithoutAccountPem() = serve("first") { server ->
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val expected = File(directory, "user-ca.der").readBytes()
        assertTrue("The fixture CA must really be installed in this Android user",
            store.aliases().toList().any { it.startsWith("user:") && store.getCertificate(it).encoded.contentEquals(expected) })
        account(server).useClient { request(it, server, success = true) }
    }

    @Test fun renewalUnderTheSameCaNeedsNoAccountChange() {
        serve("first") { first -> serve("renewed") { second ->
            assertFalse(load("first").getCertificate("server").encoded.contentEquals(load("renewed").getCertificate("server").encoded))
            // Same root, changed server key and certificate. Reuse the same production TLS manager.
            val client = BitwardenApiFactory.createOkHttpClient(accountHosts = setOf("localhost"))
            client.useClient { request(it, first, success = true); request(it, second, success = true) }
        } }
    }

    @Test fun officialHostRejectsUserAndAccountCa() = serve("official") { server ->
        val pem = "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getEncoder().encodeToString(File(directory, "user-ca.der").readBytes()) + "\n-----END CERTIFICATE-----\n"
        account(server, "vault.bitwarden.com", BitwardenTlsConfig(caCertificatePem = pem)).useClient {
            request(it, server, "vault.bitwarden.com", success = false)
        }
    }

    @Test fun officialLookingSuffixIsStillASelfHostedHost() = serve("suffix") { server ->
        account(server, "bitwarden.com.example.test").useClient { request(it, server, "bitwarden.com.example.test", success = true) }
    }

    @Test fun http2CannotCarryUserCaTrustToOfficialHost() = serve("coalescing", http2 = true) { server ->
        val draft = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            baseUrl = server.url("/").newBuilder().host("self.example.test").build().toString(),
            apiUrl = server.url("/").newBuilder().host("vault.bitwarden.com").build().toString())
        val env = requireNotNull(BitwardenEnvironmentResolver.resolve(draft, UUID.randomUUID().toString()).value)
        BitwardenApiFactory.createAccountClient(env).newBuilder().dns(loopbackDns).build().useClient { client ->
            server.enqueue(MockResponse().setBody("first"))
            client.newCall(Request.Builder().url(server.url("/api/sync").newBuilder().host("self.example.test").build()).build()).execute().use {
                assertEquals("This regression must actually negotiate HTTP/2", Protocol.HTTP_2, it.protocol)
                assertEquals("first", it.body!!.string())
            }
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            request(client, server, "vault.bitwarden.com", success = false)
        }
    }

    @Test fun hostnameMismatchIsRejected() = serve("wrong") { server ->
        account(server).useClient { request(it, server, success = false) }
    }

    @Test fun expiredServerCertificateIsRejected() = serve("expired") { server ->
        account(server).useClient { request(it, server, success = false) }
    }

    @Test fun uninstalledCaIsRejected() = serve("unknown") { server ->
        account(server).useClient { request(it, server, success = false) }
    }

    @Test fun foreignTransfersDoNotInheritAndroidUserCa() = serve("first") { server ->
        BitwardenApiFactory.createForeignTransferClient().useClient { request(it, server, success = false) }
    }

    @Test fun ordinaryNetworkClientsKeepSystemTrust() = serve("first") { server ->
        OkHttpClient().useClient { request(it, server, success = false) }
    }

    @Test fun revokedSessionCannotSendHttp() = serve("first") { server ->
        BitwardenApiFactory.createAccountClient(environment(server), requestIsCurrent = { false }).useClient {
            request(it, server, success = false)
        }
    }

    @Test fun removedUserCaIsRejected() = serve("first") { server ->
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val expected = File(directory, "user-ca.der").readBytes()
        assertFalse(store.aliases().toList().any { it.startsWith("user:") && store.getCertificate(it).encoded.contentEquals(expected) })
        account(server).useClient { request(it, server, success = false) }
    }

    private fun environment(server: MockWebServer, host: String = "localhost", tls: BitwardenTlsConfig = BitwardenTlsConfig()) =
        requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            baseUrl = server.url("/").newBuilder().host(host).build().toString(), tls = tls), UUID.randomUUID().toString()).value)

    private fun account(server: MockWebServer, host: String = "localhost", tls: BitwardenTlsConfig = BitwardenTlsConfig()) =
        BitwardenApiFactory.createAccountClient(environment(server, host, tls)).newBuilder()
            .dns(loopbackDns).build()

    private val loopbackDns = object : okhttp3.Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
    }

    private fun request(client: OkHttpClient, server: MockWebServer, host: String = "localhost", success: Boolean) {
        server.enqueue(MockResponse().setBody("synthetic-response"))
        val outcome = runCatching {
            client.newCall(Request.Builder().url(server.url("/api/sync").newBuilder().host(host).build()).build()).execute().use {
                assertEquals("synthetic-response", it.body!!.string())
            }
        }
        if (success) {
            outcome.getOrThrow()
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        } else {
            assertTrue("Rejected TLS/session must fail the request", outcome.exceptionOrNull() is java.io.IOException)
            while (true) {
                val received = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
                assertNull("No HTTP may reach a rejected peer", received.method)
            }
        }
    }

    private fun <T> OkHttpClient.useClient(block: (OkHttpClient) -> T): T = try { block(this) } finally {
        dispatcher.cancelAll(); connectionPool.evictAll(); dispatcher.executorService.shutdown()
    }

    private fun serve(name: String, http2: Boolean = false, action: (MockWebServer) -> Unit) {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(load(name), PASSWORD.toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = MockWebServer()
        server.useHttps(ssl.socketFactory, false)
        server.protocols = if (http2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try { action(server) } finally { server.shutdown() }
    }

    private fun save(name: String, leaf: Identity, root: Identity) {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null); setKeyEntry("server", leaf.pair.private, PASSWORD.toCharArray(), arrayOf(leaf.cert, root.cert))
        }
        File(directory, "$name.p12").outputStream().use { store.store(it, PASSWORD.toCharArray()) }
    }

    private fun load(name: String) = KeyStore.getInstance("PKCS12").apply {
        File(directory, "$name.p12").inputStream().use { load(it, PASSWORD.toCharArray()) }
    }

    private class Identity(val pair: KeyPair, val cert: X509Certificate)
    private fun certificate(name: String, issuer: Identity? = null, hosts: List<String> = emptyList(), ca: Boolean = false, expired: Boolean = false): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name("CN=$name")
        val issuerName = issuer?.cert?.subjectX500Principal?.encoded?.let(X500Name::getInstance) ?: subject
        fun extension(oid: ASN1ObjectIdentifier, value: ASN1Encodable) = Extension(oid, true, DEROctetString(value.toASN1Primitive().encoded))
        val extensions = mutableListOf(extension(Extension.basicConstraints, BasicConstraints(ca)),
            extension(Extension.keyUsage, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment)))
        if (hosts.isNotEmpty()) extensions += extension(Extension.subjectAlternativeName, GeneralNames(hosts.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()))
        val tbs = DERSequence(arrayOf(DERTaggedObject(true, 0, ASN1Integer(2)), ASN1Integer(System.nanoTime().let { if (it < 0) -it else it }), algorithm, issuerName,
            DERSequence(arrayOf(Time(Date(1_577_836_800_000L)), Time(Date(if (expired) 1_609_459_200_000L else 4_102_444_800_000L)))), subject,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded), DERTaggedObject(true, 3, Extensions(extensions.toTypedArray()))))
        val signed = Signature.getInstance("SHA256withRSA").apply { initSign(issuer?.pair?.private ?: pair.private); update(tbs.encoded) }.sign()
        val encoded = DERSequence(arrayOf(tbs, algorithm, DERBitString(signed))).encoded
        return Identity(pair, CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate)
    }

    companion object { private const val PASSWORD = "synthetic-user-ca-328" }
}

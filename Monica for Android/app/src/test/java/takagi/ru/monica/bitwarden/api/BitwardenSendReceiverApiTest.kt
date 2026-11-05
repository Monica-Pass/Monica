package takagi.ru.monica.bitwarden.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.service.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class BitwardenSendReceiverApiTest {
    @Test fun tokenUsesLiteralPrefixedIdentityAndDoesNotRetainSetCookie(): Unit = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val link = link(server)
            fixture.api().use { api ->
                val target = link.target(reviewed = true)
                server.enqueue(MockResponse().setBody("""{"error":"invalid_request","send_access_error_type":"password_hash_b64_required"}""")
                    .setResponseCode(400).addHeader("Set-Cookie", "account-cookie=never-reuse"))
                server.enqueue(token())
                assertEquals(BitwardenSendReceiverChallenge.PASSWORD,
                    (api.token(target, ID, BitwardenSendReceiverCredential.none()) as BitwardenSendReceiverTokenReply.Challenge).challenge)
                assertTrue(api.token(target, ID, BitwardenSendReceiverCredential.password("hash+with/padding=")) is BitwardenSendReceiverTokenReply.Granted)
                val first = server.takeRequest(2, TimeUnit.SECONDS)!!
                val second = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/deployment/identity/connect/token", first.path)
                assertEquals("POST", first.method)
                assertEquals("client_id=send&grant_type=send_access&scope=api.send.access&send_id=$ID", first.body.readUtf8())
                assertTrue(second.body.readUtf8().contains("password_hash_b64=hash%2Bwith%2Fpadding%3D"))
                listOf(first, second).forEach { request ->
                    listOf("Authorization", "Cookie", "Referer", "Origin", "Auth-Email", "Proxy-Authorization").forEach {
                        assertNull(request.getHeader(it))
                    }
                    assertFalse(request.path!!.contains(KEY))
                    assertEquals("no-store", request.getHeader("Cache-Control"))
                }
            }
            link.close()
        }
    }

    @Test fun emailGrantCarriesOnlyTheExplicitStageAndPreservesAntiEnumerationCode(): Unit = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val target = link(server).use { it.target(reviewed = true) }
            fixture.api().use { api ->
                repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody(
                    """{"error":"invalid_request","send_access_error_type":"email_and_otp_required","error_description":"must-not-surface"}""")) }
                val request = api.token(target, ID, BitwardenSendReceiverCredential.email("allowed@example.test"))
                val verify = api.token(target, ID, BitwardenSendReceiverCredential.emailCode("allowed@example.test", "1234-code"))
                assertEquals(BitwardenSendReceiverChallenge.EMAIL_CODE, (request as BitwardenSendReceiverTokenReply.Challenge).challenge)
                assertEquals(BitwardenSendReceiverChallenge.EMAIL_CODE, (verify as BitwardenSendReceiverTokenReply.Challenge).challenge)
                val first = server.takeRequest().body.readUtf8()
                val second = server.takeRequest().body.readUtf8()
                assertTrue(first.endsWith("email=allowed%40example.test"))
                assertFalse(first.contains("otp="))
                assertTrue(second.endsWith("email=allowed%40example.test&otp=1234-code"))
                assertFalse(request.toString().contains("must-not-surface"))
            }
        }
    }

    @Test fun foreignSignedStorageGetsNoSendBearerAndBytesAreNotTransformed(): Unit = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val storage = fixture.server()
            val target = link(server).use { it.target(reviewed = true) }
            val bytes = ByteArray(90_012) { (it % 253).toByte() }
            val signed = storage.url("/blob?capability=synthetic%2Btoken")
            fixture.api().use { api ->
                server.enqueue(MockResponse().setBody("""{"id":"file-id","url":"$signed"}"""))
                storage.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val download = api.authorizeFile(target, "send-token", "file-id")
                val output = ByteArrayOutputStream()
                api.download(download, bytes.size.toLong(), output, {}, { _, _ -> })
                assertArrayEquals(bytes, output.toByteArray())
                val authorized = server.takeRequest()
                assertEquals("/deployment/api/sends/access/file/file-id", authorized.path)
                assertEquals("Bearer send-token", authorized.getHeader("Authorization"))
                assertEquals(0L, authorized.body.size)
                val fetched = storage.takeRequest()
                assertEquals("/blob?capability=synthetic%2Btoken", fetched.path)
                listOf("Authorization", "Cookie", "Referer", "Origin", "Proxy-Authorization").forEach { assertNull(fetched.getHeader(it)) }
                assertEquals("identity", fetched.getHeader("Accept-Encoding"))
            }
        }
    }

    @Test fun rejectsCredentialRedirectsAndNeverRetriesAnUncertainCountedRequest(): Unit = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val foreign = fixture.server()
            val target = link(server).use { it.target(reviewed = true) }
            fixture.api().use { api ->
                server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/steal")))
                val result = api.token(target, ID, BitwardenSendReceiverCredential.password("private-hash"))
                assertEquals(BitwardenSendReceiverFailure.INVALID_TARGET, (result as BitwardenSendReceiverTokenReply.Rejected).code)
                assertEquals(0, foreign.requestCount)
                server.takeRequest()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                val error = runCatching { api.access(target, "send-token") }.exceptionOrNull() as BitwardenSendReceiverException
                assertEquals(BitwardenSendReceiverFailure.OUTCOME_UNKNOWN, error.code)
                assertEquals(2, server.requestCount)
                assertFalse(error.toString().contains("send-token"))
            }
        }
    }

    @Test fun rejectsInconsistentFileReceiptsAndCancellationClosesTheLiveDownload(): Unit = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val target = link(server).use { it.target(reviewed = true) }
            fixture.api().use { api ->
                server.enqueue(MockResponse().setBody("""{"id":"wrong-file","url":"${server.url("/blob")}"}"""))
                val error = runCatching { api.authorizeFile(target, "send-token", "file-id") }.exceptionOrNull() as BitwardenSendReceiverException
                assertEquals(BitwardenSendReceiverFailure.INVALID_RESPONSE, error.code)
                val entered = CompletableDeferred<Unit>()
                server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024))).throttleBody(1024, 1, TimeUnit.SECONDS))
                val pending = async {
                    api.download(BitwardenSendReceiverDownload("file-id", server.url("/blob")), 64L * 1024,
                        ByteArrayOutputStream(), {}, { _, _ -> entered.complete(Unit) })
                }
                entered.await(); pending.cancel()
                assertTrue(runCatching { pending.await() }.isFailure)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun serviceUnavailableRetryAfterZeroNeverReplaysGrantOrCountedPosts(): Unit = runBlocking {
        Fixture().use { fixture ->
            listOf("grant", "access", "file").forEach { operation ->
                val server = fixture.server()
                val target = link(server).use { it.target(reviewed = true) }
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                    .setBody("""{"error":"temporary_server_error"}"""))
                // A replay would succeed, making the first response and request-count assertions fail.
                server.enqueue(when (operation) {
                    "grant" -> token()
                    "access" -> MockResponse().setBody("""{"id":"send-id","type":0,"text":{"text":"synthetic-ciphertext"}}""")
                    else -> MockResponse().setBody("""{"id":"file-id","url":"${server.url("/blob")}"}""")
                })
                fixture.api().use { api ->
                    if (operation == "grant") {
                        val result = api.token(target, ID, BitwardenSendReceiverCredential.email("allowed@example.test"))
                        assertTrue(result is BitwardenSendReceiverTokenReply.Rejected)
                        assertEquals(BitwardenSendReceiverFailure.NETWORK, (result as BitwardenSendReceiverTokenReply.Rejected).code)
                    } else {
                        val error = runCatching {
                            if (operation == "access") api.access(target, "send-token")
                            else api.authorizeFile(target, "send-token", "file-id")
                        }.exceptionOrNull()
                        assertTrue(error is BitwardenSendReceiverException)
                        assertEquals(BitwardenSendReceiverFailure.OUTCOME_UNKNOWN, (error as BitwardenSendReceiverException).code)
                    }
                    val sent = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("POST", sent.method)
                    if (operation == "grant") {
                        assertTrue(sent.body.readUtf8().endsWith("email=allowed%40example.test"))
                        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
                    } else assertEquals(0L, sent.body.size)
                    assertEquals("$operation must consume only the initial HTTP response", 1, server.requestCount)
                    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                }
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val servers = mutableListOf<MockWebServer>()
        private val password = "synthetic-test-key".toCharArray()
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val name = X500Name("CN=localhost")
        private val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(System.currentTimeMillis() - 60_000),
                Date(System.currentTimeMillis() + 3_600_000), name, pair.public).apply {
                addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"), GeneralName(GeneralName.iPAddress, "127.0.0.1"))))
            }.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
        private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(KeyStore.getInstance("JKS").apply { load(null); setCertificateEntry("test", certificate) })
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        fun api() = BitwardenSendReceiverApi.withTrustForTests(trust)
        fun server(): MockWebServer {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(KeyStore.getInstance("JKS").apply { load(null); setKeyEntry("test", pair.private, password, arrayOf(certificate)) }, password)
            }
            val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
            return MockWebServer().also { it.useHttps(tls.socketFactory, false); it.start(); servers += it }
        }
        override fun close() { servers.forEach { it.shutdown() }; password.fill('\u0000') }
    }

    companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val KEY = "Pgui0FK85cNhBGWHAlBHBw"
        private fun link(server: MockWebServer) = BitwardenSendReceiverLink.parse("${server.url("/deployment/")}#/send/$ID/$KEY")
        private fun token() = MockResponse().setBody("""{"access_token":"synthetic-send-token","expires_in":120,"token_type":"Bearer","scope":"api.send.access"}""")
    }
}

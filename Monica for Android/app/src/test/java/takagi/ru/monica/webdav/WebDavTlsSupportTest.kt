package takagi.ru.monica.webdav

import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实 TLS 握手验证三档校验策略，而不是断言 OkHttp 的内部字段。
 *
 * MockWebServer 出示一张自签名证书；由于测试 JVM 的信任库里没有这张证书，
 * 它同时满足「自签名」与「不受信」两种场景，正好覆盖三档的分界行为。
 *
 * 注意：这些用例不依赖 Android framework，因此可以在 JVM 单元测试里跑。
 * [WebDavGateway] 的完整客户端会引入 android.util.Base64 与 BuildConfig，
 * 故这里直接测 [WebDavTlsSupport]，与 Gateway 的接线由
 * [WebDavTlsGatewayWiringTest] 用源码断言覆盖。
 */
class WebDavTlsSupportTest {

    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        server?.shutdown()
        server = null
        WebDavTlsSettings.resetForTest()
    }

    @Test
    fun systemDefaultMode_rejectsSelfSignedCertificate() {
        val url = startSelfSignedServer()
        val error = get(clientFor(WebDavTlsMode.SYSTEM_DEFAULT), url)

        assertTrue(
            "Expected a handshake failure, got: $error",
            error is SSLHandshakeException
        )
    }

    @Test
    fun allowSelfSignedMode_acceptsSelfSignedCertificate() {
        val url = startSelfSignedServer()
        val client = clientFor(WebDavTlsMode.ALLOW_SELF_SIGNED)

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun allowUntrustedMode_acceptsSelfSignedCertificate() {
        val url = startSelfSignedServer()
        val client = clientFor(WebDavTlsMode.ALLOW_UNTRUSTED)

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    /**
     * 自签名档必须继续校验主机名。这是它与放行档最重要的区别：证书为
     * `localhost` 签发，用 `127.0.0.1` 访问时应被主机名校验拦下。
     */
    @Test
    fun allowSelfSignedMode_stillVerifiesHostname() {
        val url = startSelfSignedServer(certificateHostname = "wrong-host.invalid")
        val error = get(clientFor(WebDavTlsMode.ALLOW_SELF_SIGNED), url)

        assertTrue(
            "Hostname verification must remain active, got: $error",
            error is SSLHandshakeException || error is javax.net.ssl.SSLPeerUnverifiedException
        )
    }

    /** 放行档同时关闭主机名校验。 */
    @Test
    fun allowUntrustedMode_skipsHostnameVerification() {
        val url = startSelfSignedServer(certificateHostname = "wrong-host.invalid")
        val client = clientFor(WebDavTlsMode.ALLOW_UNTRUSTED)

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    /**
     * 自签名档在服务器证书由受信 CA 签发时不得改变行为。
     *
     * 这里搭一条「私有 CA -> 叶证书」的真实链，并把「信任该 CA 的
     * trust manager」作为容忍层的基准。叶证书不是自签名的，因此走的是
     * `checkServerTrusted` 里系统校验直接通过的那条路径——也就是说，
     * 容忍逻辑对正常链是完全透明的。
     */
    @Test
    fun allowSelfSignedMode_doesNotChangeTrustedChainBehaviour() {
        val rootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Monica Test Root")
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .signedBy(rootCa)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(leaf, rootCa.certificate)
            .build()

        val started = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200))
            start(InetAddress.getByName("localhost"), 0)
        }
        server = started

        val trustingCa = HandshakeCertificates.Builder()
            .addTrustedCertificate(rootCa.certificate)
            .build()
            .trustManager
        val tolerant = WebDavTlsSupport.selfSignedTolerantTrustManagerForTest(trustingCa)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tolerant), SecureRandom())
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tolerant)
            .build()

        client.newCall(Request.Builder().url(started.probeUrl()).build()).execute()
            .use { response -> assertEquals(200, response.code) }
    }

    /**
     * 容忍层不得放过「链尾不是自签名」的失败，例如缺失中间证书、
     * 或由一个客户端并不信任的真实 CA 签发的链。
     */
    @Test
    fun allowSelfSignedMode_rejectsChainWhoseAnchorIsNotSelfSigned() {
        val rootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Monica Untrusted Root")
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .signedBy(rootCa)
            .build()
        // 只出示叶证书，不带 CA：链尾是叶证书，且它不是自签名的。
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(leaf)
            .build()

        val started = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200))
            start(InetAddress.getByName("localhost"), 0)
        }
        server = started

        val error = get(clientFor(WebDavTlsMode.ALLOW_SELF_SIGNED), started.probeUrl())
        assertTrue(
            "A non-self-signed untrusted anchor must still be rejected, got: $error",
            error is SSLHandshakeException
        )
    }

    /** SSLSocketFactory 构造有开销，同一档位必须复用缓存实例。 */
    @Test
    fun relaxedModes_reuseCachedSocketFactory() {
        val first = clientFor(WebDavTlsMode.ALLOW_SELF_SIGNED).sslSocketFactory
        val second = clientFor(WebDavTlsMode.ALLOW_SELF_SIGNED).sslSocketFactory
        assertSame(first, second)
    }

    @Test
    fun unknownStoredValue_fallsBackToStrictDefault() {
        assertEquals(WebDavTlsMode.SYSTEM_DEFAULT, WebDavTlsMode.fromStorage(null))
        assertEquals(WebDavTlsMode.SYSTEM_DEFAULT, WebDavTlsMode.fromStorage(""))
        assertEquals(WebDavTlsMode.SYSTEM_DEFAULT, WebDavTlsMode.fromStorage("ALLOW_EVERYTHING"))
        assertEquals(
            WebDavTlsMode.ALLOW_UNTRUSTED,
            WebDavTlsMode.fromStorage("ALLOW_UNTRUSTED")
        )
    }

    @Test
    fun isRelaxed_onlyTrueForNonDefaultModes() {
        assertFalse(WebDavTlsMode.SYSTEM_DEFAULT.isRelaxed)
        assertTrue(WebDavTlsMode.ALLOW_SELF_SIGNED.isRelaxed)
        assertTrue(WebDavTlsMode.ALLOW_UNTRUSTED.isRelaxed)
    }

    /** 未绑定 Context 时必须返回最严格的档位。 */
    @Test
    fun currentMode_defaultsToStrictWithoutPersistence() {
        WebDavTlsSettings.resetForTest()
        assertEquals(WebDavTlsMode.SYSTEM_DEFAULT, WebDavTlsSettings.currentMode())
    }

    private fun clientFor(mode: WebDavTlsMode): OkHttpClient =
        OkHttpClient.Builder()
            .apply { WebDavTlsSupport.configure(this, mode) }
            .build()

    /**
     * MockWebServer.url() 会返回 `127.0.0.1`，而测试证书的 SAN 是主机名，
     * 直接使用会先撞上主机名校验，掩盖我们真正想验证的链校验行为。
     */
    private fun MockWebServer.probeUrl(host: String = "localhost"): String =
        url("/probe").newBuilder().host(host).build().toString()

    private fun startSelfSignedServer(certificateHostname: String = "localhost"): String {
        val certificate = HeldCertificate.Builder()
            .commonName(certificateHostname)
            .addSubjectAlternativeName(certificateHostname)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()

        val started = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200))
            start(InetAddress.getByName("localhost"), 0)
        }
        server = started
        return started.probeUrl()
    }

    private fun get(client: OkHttpClient, url: String): Throwable? = try {
        client.newCall(Request.Builder().url(url).build()).execute().close()
        null
    } catch (error: IOException) {
        error
    }
}

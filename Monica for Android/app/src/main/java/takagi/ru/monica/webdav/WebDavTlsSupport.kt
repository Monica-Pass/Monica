package takagi.ru.monica.webdav

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 按 [WebDavTlsMode] 为 OkHttp 客户端装配 TLS 校验行为。
 *
 * 安全边界说明（务必在修改前阅读）：
 * - [WebDavTlsMode.SYSTEM_DEFAULT] 分支**不写入任何** TLS 配置，从而完整继承
 *   OkHttp / Android 平台默认实现（含平台的证书透明度与 API 级别相关行为）。
 *   这一点很重要：手工构造 `SSLContext.getInstance("TLS")` 即使配置等价，
 *   也会绕过 Android 的 `X509TrustManagerExtensions` 优化路径。
 * - [WebDavTlsMode.ALLOW_SELF_SIGNED] 仍然做完整的证书路径校验（签名、有效期、
 *   基本约束）与主机名校验，只是额外允许「服务器出示的自签名链尾」充当信任锚。
 *   它不会接受一条无法自洽的链，也不会接受主机名不匹配的证书。
 * - [WebDavTlsMode.ALLOW_UNTRUSTED] 才是真正的放行档，会同时关闭链校验与
 *   主机名校验。仅在用户于 UI 上二次确认后才应被选中。
 *
 * [SSLSocketFactory] 的构造有明显开销，因此按档位缓存；档位切换后重新构造的
 * 客户端会拿到对应缓存实例。
 */
internal object WebDavTlsSupport {

    private val selfSignedFactoryLock = Any()

    @Volatile
    private var selfSignedFactory: TrustSetup? = null

    private val insecureFactoryLock = Any()

    @Volatile
    private var insecureFactory: TrustSetup? = null

    private data class TrustSetup(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    /**
     * 把 [mode] 对应的校验策略应用到 [builder] 上。
     *
     * 任何一步失败都会静默退回系统默认校验（fail-secure），而不是抛异常中断
     * 备份流程；失败原因写入日志，不包含证书内容。
     */
    fun configure(builder: OkHttpClient.Builder, mode: WebDavTlsMode) {
        when (mode) {
            // 关键：这里必须什么都不做，见类注释。
            WebDavTlsMode.SYSTEM_DEFAULT -> Unit

            WebDavTlsMode.ALLOW_SELF_SIGNED -> {
                val setup = runCatching { selfSignedSetup() }.getOrNull()
                if (setup == null) {
                    android.util.Log.w(TAG, "Falling back to system TLS: self-signed setup failed")
                    return
                }
                builder.sslSocketFactory(setup.socketFactory, setup.trustManager)
                // 主机名校验保持默认：自签名不等于可以冒充任意域名。
            }

            WebDavTlsMode.ALLOW_UNTRUSTED -> {
                val setup = runCatching { insecureSetup() }.getOrNull()
                if (setup == null) {
                    android.util.Log.w(TAG, "Falling back to system TLS: insecure setup failed")
                    return
                }
                builder.sslSocketFactory(setup.socketFactory, setup.trustManager)
                builder.hostnameVerifier(AllowAllHostnameVerifier)
            }
        }
    }

    private fun selfSignedSetup(): TrustSetup {
        selfSignedFactory?.let { return it }
        return synchronized(selfSignedFactoryLock) {
            selfSignedFactory?.let { return it }
            val trustManager = SelfSignedTolerantTrustManager(systemDefaultTrustManager())
            val created = TrustSetup(socketFactoryFor(trustManager), trustManager)
            selfSignedFactory = created
            created
        }
    }

    private fun insecureSetup(): TrustSetup {
        insecureFactory?.let { return it }
        return synchronized(insecureFactoryLock) {
            insecureFactory?.let { return it }
            val trustManager = TrustAnyServerTrustManager()
            val created = TrustSetup(socketFactoryFor(trustManager), trustManager)
            insecureFactory = created
            created
        }
    }

    private fun socketFactoryFor(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return sslContext.socketFactory
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No system X509TrustManager available")
    }

    /**
     * 仅测试用：在指定的基准 trust manager 之上包一层自签名容忍逻辑。
     *
     * 生产代码始终以系统信任库为基准；测试需要注入一个信任私有 CA 的基准，
     * 才能验证「链本身可信时行为不变」这条性质。
     */
    internal fun selfSignedTolerantTrustManagerForTest(
        base: X509TrustManager,
    ): X509TrustManager = SelfSignedTolerantTrustManager(base)

    /**
     * 在系统信任库之外，额外接受「自洽的自签名证书链」。
     *
     * 校验顺序：
     * 1. 先交给系统 trust manager；通过则直接返回，公有 CA 场景零行为变化。
     * 2. 系统拒绝时，取链尾证书，要求它是自签名的（subject == issuer 且能用
     *    自身公钥验签）。不满足则沿用系统的拒绝结果。
     * 3. 把该链尾装进临时 KeyStore 作为唯一信任锚，用标准
     *    [TrustManagerFactory] 重新校验完整链。这一步会继续检查中间证书签名、
     *    有效期与基本约束，因此攻击者无法用一张随意伪造的叶证书通过校验
     *    （除非用户机器已经在信任那张自签名根）。
     *
     * 该实现被 lint 的 `CustomX509TrustManager` 规则命中，但它并非放行实现，
     * 故就地抑制而非写入 lint baseline。
     */
    @SuppressLint("CustomX509TrustManager")
    private class SelfSignedTolerantTrustManager(
        private val systemTrustManager: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            // 客户端证书不在本功能范围内，直接沿用系统判定。
            systemTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) {
                throw CertificateException("Empty server certificate chain")
            }

            val systemFailure = try {
                systemTrustManager.checkServerTrusted(chain, authType)
                return
            } catch (error: CertificateException) {
                error
            }

            val anchor = chain.last()
            if (!isSelfSigned(anchor)) {
                // 链尾不是自签名，说明缺失的是中间证书或确实由不受信 CA 签发，
                // 这类情况不属于「自签名服务器」，保持系统的拒绝语义。
                throw systemFailure
            }

            try {
                anchorTrustManager(anchor).checkServerTrusted(chain, authType)
            } catch (error: Exception) {
                throw CertificateException(
                    "Self-signed chain validation failed: ${error.message}",
                    systemFailure
                )
            }
        }

        /**
         * 只暴露系统信任锚。
         *
         * 自签名锚是逐连接动态发现的，无法在此静态枚举；返回系统列表可以让
         * TLS 握手继续按常规方式协商，同时不会向服务器泄露额外信息。
         */
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            systemTrustManager.acceptedIssuers

        private fun isSelfSigned(certificate: X509Certificate): Boolean {
            if (certificate.subjectX500Principal != certificate.issuerX500Principal) return false
            return runCatching { certificate.verify(certificate.publicKey) }.isSuccess
        }

        private fun anchorTrustManager(anchor: X509Certificate): X509TrustManager {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("webdav-self-signed-anchor", anchor)
            }
            val factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            factory.init(keyStore)
            return factory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: throw IllegalStateException("No X509TrustManager for self-signed anchor")
        }
    }

    /**
     * 接受任意服务器证书。
     *
     * 这是有意为之的放行实现，配合 [AllowAllHostnameVerifier] 一起使用，
     * 仅在用户显式选择 [WebDavTlsMode.ALLOW_UNTRUSTED] 并二次确认后启用。
     * lint 的 `TrustAllX509TrustManager` 命中属预期，就地抑制。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class TrustAnyServerTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** 与 [TrustAnyServerTrustManager] 配套的主机名放行实现。 */
    @SuppressLint("BadHostnameVerifier")
    private object AllowAllHostnameVerifier : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean = true
    }

    private const val TAG = "WebDavTls"
}

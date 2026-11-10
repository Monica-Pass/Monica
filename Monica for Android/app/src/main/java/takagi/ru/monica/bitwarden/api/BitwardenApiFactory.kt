package takagi.ru.monica.bitwarden.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Bitwarden API 客户端工厂
 * 
 * 创建针对不同服务器端点的 API 客户端
 * 支持官方服务和自托管服务
 */
object BitwardenApiFactory {
    
    private const val TAG = "BitwardenApiFactory"
    
    // 官方服务端点（US）
    const val OFFICIAL_VAULT_URL = "https://vault.bitwarden.com"
    const val OFFICIAL_IDENTITY_URL = "https://identity.bitwarden.com"
    const val OFFICIAL_API_URL = "https://api.bitwarden.com"

    // 官方服务端点（EU）
    const val OFFICIAL_EU_VAULT_URL = "https://vault.bitwarden.eu"
    const val OFFICIAL_EU_IDENTITY_URL = "https://identity.bitwarden.eu"
    const val OFFICIAL_EU_API_URL = "https://api.bitwarden.eu"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }
    
    enum class HeaderProfile {
        MONICA_DEFAULT,
        KEYGUARD_FALLBACK
    }

    private data class HeaderSpec(
        val majorVersion: String,
        val fullVersion: String
    )

    // Monica 当前默认请求指纹
    private const val MONICA_CHROME_MAJOR_VERSION = "131"
    private const val MONICA_CHROME_FULL_VERSION = "$MONICA_CHROME_MAJOR_VERSION.0.6778.140"
    // Keyguard 当前使用的请求指纹
    private const val KEYGUARD_CHROME_MAJOR_VERSION = "126"
    private const val KEYGUARD_CHROME_FULL_VERSION = "$KEYGUARD_CHROME_MAJOR_VERSION.0.6478.114"
    
    /**
     * 创建 OkHttp 客户端
     * 
     * @param enableLogging 是否启用日志 (仅用于调试)
     */
    fun createOkHttpClient(
        enableLogging: Boolean = false,
        refererUrl: String? = null,
        headerProfile: HeaderProfile = HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null
    ): OkHttpClient {
        val headerSpec = getHeaderSpec(headerProfile)
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 添加 Keyguard 使用的 Cloudflare 绕过 headers
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                
                builder.header("User-Agent", buildUserAgent(headerSpec.fullVersion))
                builder.header("Keyguard-Client", "1")
                builder.header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                builder.header("Sec-Ch-Ua", """"Not.A/Brand";v="8", "Chromium";v="${headerSpec.majorVersion}"""")
                builder.header("Sec-Ch-Ua-Mobile", "?0")
                builder.header("Sec-Ch-Ua-Platform", "Linux")
                // Bitwarden 服务端根据客户端版本决定是否返回 Type 5 (SSH Key) 等新类型数据
                // 不声明版本时服务端会降级为 Type 1 并丢弃 sshKey 字段
                builder.header("Bitwarden-Client-Name", "desktop")
                builder.header("Bitwarden-Client-Version", "2025.1.0")
                if (isRefererApplied(headerProfile, refererUrl)) {
                    builder.header("referer", ensureTrailingSlash(refererUrl!!.trim()))
                }
                
                chain.proceed(builder.build())
            }
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }

        configureTls(builder, tlsConfig)
        return builder.build()
    }

    fun headerProfileName(profile: HeaderProfile): String = when (profile) {
        HeaderProfile.MONICA_DEFAULT -> "monica_default"
        HeaderProfile.KEYGUARD_FALLBACK -> "keyguard_fallback"
    }

    /** Scope and lease checks run before a connection (and therefore before a TLS handshake). */
    fun createAccountClient(
        environment: BitwardenEnvironment,
        headerProfile: HeaderProfile = HeaderProfile.MONICA_DEFAULT,
        requestIsCurrent: () -> Boolean = { true },
    ): OkHttpClient {
        // This freshly created base owns its pool. No account or foreign client shares it.
        val builder = createOkHttpClient(headerProfile = headerProfile, tlsConfig = environment.tls)
            .newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        builder.interceptors().add(0, okhttp3.Interceptor { chain ->
            if (!requestIsCurrent()) throw java.io.IOException("Bitwarden transport session changed")
            if (!BitwardenTransportPolicy.canUseAccountTransport(environment, chain.request().url.toString())) {
                throw java.io.IOException("Bitwarden transport destination rejected")
            }
            val request = chain.request().newBuilder().removeHeader("Referer")
            BitwardenTransportPolicy.referer(environment)?.let { request.header("Referer", it) }
            environment.headers.forEach { request.header(it.name, it.value) }
            chain.proceed(singleAttemptBody(request.build()))
        })
        return builder.build()
    }

    /** Signed foreign storage receives system trust only and no native account credentials. */
    fun createForeignTransferClient(requestIsCurrent: () -> Boolean = { true }): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                if (!requestIsCurrent()) throw java.io.IOException("Bitwarden transport session changed")
                val original = chain.request()
                if (!original.url.isHttps) throw java.io.IOException("Bitwarden transfer requires HTTPS")
                // Explicitly reject accidental caller credentials rather than silently forwarding them.
                if (listOf("Authorization", "Proxy-Authorization", "Cookie", "Referer").any { original.header(it) != null }) {
                    throw java.io.IOException("Bitwarden foreign transfer credentials rejected")
                }
                chain.proceed(singleAttemptBody(original))
            }.build()

    /** Only the typed authenticated adapter may retry a verified 401. OkHttp must not replay
     * a write for connection failure, a 503 Retry-After response or another follow-up. Each explicit
     * Retrofit retry constructs a new request; signed transfers are never retried here. */
    internal fun singleAttemptBody(request: okhttp3.Request): okhttp3.Request {
        val body = request.body ?: return request
        return request.newBuilder().method(request.method, object : okhttp3.RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
        }).build()
    }

    fun headerProfileUserAgentVersion(profile: HeaderProfile): String {
        val spec = getHeaderSpec(profile)
        return "Chrome/${spec.fullVersion}"
    }

    fun isRefererApplied(profile: HeaderProfile, refererUrl: String?): Boolean {
        val normalized = refererUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val officialUs = isOfficialServer(normalized)
        val officialEu = isOfficialEuServer(normalized)
        // keyguard 对官方服务器不发 referer，只对自建服务器发
        if (officialUs || officialEu) return false
        return true
    }
    
    /**
     * 创建 Identity API 客户端 (认证)
     * 
     * @param baseUrl Identity 服务端点
     * @param okHttpClient 可选的自定义 OkHttp 客户端
     */
    fun createIdentityApi(
        baseUrl: String = OFFICIAL_IDENTITY_URL,
        okHttpClient: OkHttpClient = createOkHttpClient()
    ): BitwardenIdentityApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        
        return retrofit.create(BitwardenIdentityApi::class.java)
    }
    
    /**
     * 创建 Vault API 客户端 (数据操作)
     * 
     * @param baseUrl API 服务端点
     * @param okHttpClient 可选的自定义 OkHttp 客户端
     */
    fun createVaultApi(
        baseUrl: String = OFFICIAL_API_URL,
        okHttpClient: OkHttpClient = createOkHttpClient()
    ): BitwardenVaultApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        
        return retrofit.create(BitwardenVaultApi::class.java)
    }
    
    /**
     * 从 Vault URL 推断其他端点 URL (用于自托管服务)
     * 
     * 自托管服务通常使用以下结构:
     * - Vault: https://your-domain.com
     * - Identity: https://your-domain.com/identity
     * - API: https://your-domain.com/api
     */
    fun inferServerUrls(vaultUrl: String): ServerUrls {
        val normalizedUrl = vaultUrl.trimEnd('/')

        return when {
            isOfficialEuServer(normalizedUrl) -> {
                ServerUrls(
                    vault = OFFICIAL_EU_VAULT_URL,
                    identity = OFFICIAL_EU_IDENTITY_URL,
                    api = OFFICIAL_EU_API_URL
                )
            }

            isOfficialServer(normalizedUrl) -> {
            ServerUrls(
                vault = OFFICIAL_VAULT_URL,
                identity = OFFICIAL_IDENTITY_URL,
                api = OFFICIAL_API_URL
            )
            }

            else -> {
            ServerUrls(
                vault = normalizedUrl,
                identity = "$normalizedUrl/identity",
                api = "$normalizedUrl/api"
            )
            }
        }
    }
    
    /**
     * 检查是否为官方服务
     *
     * 使用严格的域名后缀匹配，避免自建 Vaultwarden 的 URL 中偶然包含 "bitwarden" 子串
     * （例如路径、子域名等）被误判为官方服务器。
     */
    fun isOfficialServer(url: String): Boolean {
        val normalized = url.lowercase().trimEnd('/')
        if (normalized == OFFICIAL_VAULT_URL.lowercase()) return true
        // 严格匹配：域名部分以 bitwarden.com 结尾（不是 URL 任意位置 contains）
        val host = runCatching { java.net.URI(normalized).host }.getOrNull() ?: return false
        return host == "bitwarden.com" || host.endsWith(".bitwarden.com")
    }

    /**
     * 检查是否为官方 EU 服务
     */
    fun isOfficialEuServer(url: String): Boolean {
        val normalized = url.lowercase().trimEnd('/')
        if (normalized == OFFICIAL_EU_VAULT_URL.lowercase()) return true
        val host = runCatching { java.net.URI(normalized).host }.getOrNull() ?: return false
        return host == "bitwarden.eu" || host.endsWith(".bitwarden.eu")
    }
    
    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun buildUserAgent(chromeFullVersion: String): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFullVersion Safari/537.36"
    }

    private fun getHeaderSpec(profile: HeaderProfile): HeaderSpec {
        return when (profile) {
            HeaderProfile.MONICA_DEFAULT -> HeaderSpec(
                majorVersion = MONICA_CHROME_MAJOR_VERSION,
                fullVersion = MONICA_CHROME_FULL_VERSION
            )

            HeaderProfile.KEYGUARD_FALLBACK -> HeaderSpec(
                majorVersion = KEYGUARD_CHROME_MAJOR_VERSION,
                fullVersion = KEYGUARD_CHROME_FULL_VERSION
            )
        }
    }

    private fun configureTls(
        builder: OkHttpClient.Builder,
        tlsConfig: BitwardenTlsConfig?
    ) {
        if (tlsConfig == null || tlsConfig.isEmpty()) return

        val trustManager = buildTrustManager(tlsConfig.caCertificatePem)
        val keyManagers = buildClientKeyManagers(
            enabled = tlsConfig.mtlsEnabled,
            pkcs12Base64 = tlsConfig.clientCertPkcs12Base64,
            password = tlsConfig.clientCertPassword
        )

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
    }

    private fun buildTrustManager(caCertificatePem: String?): X509TrustManager {
        val systemTrustManager = systemDefaultTrustManager()
        if (caCertificatePem.isNullOrBlank()) return systemTrustManager

        val customTrustManager = customCaTrustManager(caCertificatePem)
        return CompositeX509TrustManager(listOf(systemTrustManager, customTrustManager))
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun customCaTrustManager(caCertificatePem: String): X509TrustManager {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = certificateFactory.generateCertificates(
            ByteArrayInputStream(caCertificatePem.toByteArray(Charsets.UTF_8))
        )

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        certificates.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("ca_$index", cert)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun buildClientKeyManagers(
        enabled: Boolean,
        pkcs12Base64: String?,
        password: String?
    ): Array<KeyManager>? {
        if (!enabled) return null
        if (pkcs12Base64.isNullOrBlank()) return null

        val passwordChars = password.orEmpty().toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        val certBytes = Base64.decode(pkcs12Base64, Base64.DEFAULT)
        keyStore.load(ByteArrayInputStream(certBytes), passwordChars)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, passwordChars)
        return kmf.keyManagers
    }

    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastError: Exception? = null
            delegates.forEach { manager ->
                try {
                    manager.checkClientTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No trust manager accepted client certificate")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastError: Exception? = null
            delegates.forEach { manager ->
                try {
                    manager.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No trust manager accepted server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates
                .flatMap { it.acceptedIssuers.toList() }
                .distinctBy { it.subjectX500Principal.name + it.serialNumber }
                .toTypedArray()
        }
    }
    
    /**
     * 服务器 URL 配置
     */
    data class ServerUrls(
        val vault: String,
        val identity: String,
        val api: String
    )
}

/**
 * Bitwarden API 客户端管理器
 * 
 * 管理多个 Vault 的 API 客户端实例
 */
class BitwardenApiManager(
    private val environmentForVault: ((BitwardenVault) -> BitwardenEnvironment)? = null,
    private val vaultRequestIsCurrent: (BitwardenVault) -> Boolean = { true },
    private val attemptRequestIsCurrent: (BitwardenEnvironment) -> Boolean = { true },
    private val wrapVaultApi: ((BitwardenVault, BitwardenVaultApi) -> BitwardenVaultApi)? = null,
) {
    private companion object {
        // Physical TLS shutdown outlives UI/model cancellation; authorization is revoked below synchronously.
        val retiredPoolCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private class ClientKey(val owner: String, val revision: String, val fingerprint: String,
        val profile: BitwardenApiFactory.HeaderProfile, val foreign: Boolean) {
        override fun equals(other: Any?) = other is ClientKey && owner == other.owner &&
            revision == other.revision && fingerprint == other.fingerprint && profile == other.profile && foreign == other.foreign
        override fun hashCode() = listOf(owner, revision, fingerprint, profile, foreign).hashCode()
        override fun toString() = "BitwardenClientKey(redacted)"
    }
    private class ClientEntry(val client: OkHttpClient, val active: java.util.concurrent.atomic.AtomicBoolean)
    private val clients = mutableMapOf<ClientKey, ClientEntry>()
    private val identityApis = mutableMapOf<ClientKey, BitwardenIdentityApi>()
    private val vaultApis = mutableMapOf<ClientKey, BitwardenVaultApi>()
    private val legacyStore = BitwardenVaultEnvironmentStore(null)
    private val legacyEnvironments = mutableMapOf<String, BitwardenEnvironment>()
    private val retiredAttempts = mutableSetOf<String>()

    @Synchronized
    fun environment(vault: BitwardenVault): BitwardenEnvironment =
        environmentForVault?.invoke(vault) ?: legacyStore.resolve(vault)

    @Synchronized
    fun getIdentityApi(environment: BitwardenEnvironment,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT): BitwardenIdentityApi {
        check(environment.revision !in retiredAttempts) { "Bitwarden authentication attempt retired" }
        val key = key("attempt", environment, headerProfile)
        return identityApis.getOrPut(key) {
            BitwardenApiFactory.createIdentityApi(environment.identity.toString(), client(key, environment) { attemptRequestIsCurrent(environment) })
        }
    }

    @Synchronized
    fun getVaultApi(environment: BitwardenEnvironment,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT): BitwardenVaultApi {
        check(environment.revision !in retiredAttempts) { "Bitwarden authentication attempt retired" }
        val key = key("attempt", environment, headerProfile)
        return vaultApis.getOrPut(key) {
            BitwardenApiFactory.createVaultApi(environment.api.toString(), client(key, environment) { attemptRequestIsCurrent(environment) })
        }
    }

    @Synchronized
    fun getVaultApi(vault: BitwardenVault,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT): BitwardenVaultApi {
        val environment = environment(vault)
        val key = key("vault:${vault.id}", environment, headerProfile)
        val raw = vaultApis.getOrPut(key) {
            BitwardenApiFactory.createVaultApi(environment.api.toString(), client(key, environment) { vaultRequestIsCurrent(vault) })
        }
        // Raw transports are cached; request wrappers capture the current account generation.
        return protectVaultApi(vault, raw)
    }

    fun protectVaultApi(vault: BitwardenVault, api: BitwardenVaultApi): BitwardenVaultApi =
        wrapVaultApi?.invoke(vault, api) ?: api

    @Synchronized
    fun getIdentityApi(vault: BitwardenVault): BitwardenIdentityApi {
        val environment = environment(vault)
        val key = key("vault:${vault.id}", environment)
        return identityApis.getOrPut(key) {
            BitwardenApiFactory.createIdentityApi(environment.identity.toString(), client(key, environment) { vaultRequestIsCurrent(vault) })
        }
    }

    @Synchronized
    fun getOkHttpClient(vault: BitwardenVault): OkHttpClient {
        val environment = environment(vault)
        return client(key("vault:${vault.id}", environment), environment) { vaultRequestIsCurrent(vault) }
    }

    @Synchronized
    fun getTransferClient(vault: BitwardenVault, rawUrl: String): OkHttpClient {
        val environment = environment(vault)
        val target = BitwardenTransportPolicy.validateTransferUrl(environment, rawUrl).value
            ?: throw java.io.IOException("Bitwarden transfer destination rejected")
        return client(key("vault:${vault.id}", environment, foreign = !target.useAccountTransport), environment) {
            vaultRequestIsCurrent(vault)
        }
    }

    // Compatibility entry points for callers being migrated. Pending authentication uses the snapshot overloads.
    @Synchronized
    fun getIdentityApi(identityUrl: String, refererUrl: String? = null,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null): BitwardenIdentityApi =
        getIdentityApi(legacyEnvironment(refererUrl ?: identityUrl, identityUrl = identityUrl, tlsConfig = tlsConfig), headerProfile)

    @Synchronized
    fun getVaultApi(apiUrl: String, refererUrl: String? = null,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null): BitwardenVaultApi =
        getVaultApi(legacyEnvironment(refererUrl ?: apiUrl, apiUrl = apiUrl, tlsConfig = tlsConfig), headerProfile)

    private fun legacyEnvironment(base: String, apiUrl: String? = null, identityUrl: String? = null,
        tlsConfig: BitwardenTlsConfig?): BitwardenEnvironment {
        val resolved = BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot(
            base, apiUrl, identityUrl, tlsConfig ?: BitwardenTlsConfig()), java.util.UUID.randomUUID().toString()).value
            ?: throw java.io.IOException("Bitwarden transport configuration rejected")
        return legacyEnvironments.getOrPut(resolved.fingerprint) { resolved }
    }

    @Synchronized
    fun retireVault(vaultId: Long) {
        retire { it.owner == "vault:$vaultId" }
        legacyStore.forget(vaultId)
    }

    @Synchronized
    fun retireEnvironment(environment: BitwardenEnvironment) {
        retiredAttempts.add(environment.revision)
        retire { it.owner == "attempt" && it.revision == environment.revision }
        legacyEnvironments.entries.removeAll { it.value.revision == environment.revision }
    }

    @Synchronized
    fun isEnvironmentActive(environment: BitwardenEnvironment): Boolean = environment.revision !in retiredAttempts

    @Synchronized
    fun clearCache() {
        clients.keys.filter { it.owner == "attempt" }.forEach { retiredAttempts.add(it.revision) }
        retire { true }
        legacyEnvironments.clear()
        legacyStore.forget()
    }

    private fun key(owner: String, environment: BitwardenEnvironment,
        profile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        foreign: Boolean = false) = ClientKey(owner, environment.revision, environment.fingerprint, profile, foreign)

    private fun client(key: ClientKey, environment: BitwardenEnvironment, current: () -> Boolean): OkHttpClient =
        clients.getOrPut(key) {
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            val guard = { active.get() && current() }
            ClientEntry(if (key.foreign) BitwardenApiFactory.createForeignTransferClient(guard)
                else BitwardenApiFactory.createAccountClient(environment, key.profile, guard), active)
        }.client

    private fun retire(predicate: (ClientKey) -> Boolean) {
        val removed = clients.keys.filter(predicate)
        removed.forEach { key ->
            clients.remove(key)?.let { entry ->
                entry.active.set(false)
                entry.client.dispatcher.cancelAll()
                val retiredPool = entry.client.connectionPool
                retiredPoolCleanup.launch { retiredPool.evictAll() }
                entry.client.dispatcher.executorService.shutdown()
            }
            identityApis.remove(key)
            vaultApis.remove(key)
        }
    }
}

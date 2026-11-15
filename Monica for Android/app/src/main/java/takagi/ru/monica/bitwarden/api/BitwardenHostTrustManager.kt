package takagi.ru.monica.bitwarden.api

import android.net.http.X509TrustManagerExtensions
import androidx.annotation.Keep
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Extra trust belongs only to configured native account hosts, never to official services.
 * This does not change Android's application-wide trust or OkHttp's hostname verifier.
 */
internal class BitwardenHostTrustManager(
    private val system: X509TrustManager,
    accountHosts: Set<String>,
    private val custom: X509TrustManager?,
    private val userTrust: () -> X509TrustManager? = ::androidUserCaTrustManager,
) : X509ExtendedTrustManager() {
    private val extraTrustHosts = accountHosts.map(::canonicalHost).filterNot(::isOfficialHost).toSet()

    private fun verify(chain: Array<X509Certificate>, authType: String, host: String?): List<X509Certificate> {
        val systemFailure = try {
            return verifyWith(system, chain, authType, host)
        } catch (error: CertificateException) { error }
        if (host == null || canonicalHost(host) !in extraTrustHosts) throw systemFailure
        if (custom != null) {
            try { return verifyWith(custom, chain, authType, host) }
            catch (_: CertificateException) { /* Try the Android user's explicit CA installation. */ }
        }
        // Read the current store at each new handshake: never persist a second copy of its CAs.
        val installed = try { userTrust() } catch (_: Exception) { null }
        if (installed != null) return verifyWith(installed, chain, authType, host)
        throw systemFailure
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // Without a peer hostname there is no authority to use account-specific extra trust.
        verify(chain, authType, null)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        verify(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        verify(chain, authType, engine.handshakeSession?.peerHost)
    }

    /** Android/OkHttp discovers this host-aware chain-cleaning API by reflection. */
    @Keep
    @Suppress("unused")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> =
        verify(chain, authType, host)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        (system.acceptedIssuers.asList() + custom?.acceptedIssuers.orEmpty().asList() +
            (try { userTrust()?.acceptedIssuers } catch (_: Exception) { null }).orEmpty().asList()).toTypedArray()

    companion object {
        private fun canonicalHost(host: String): String = host.lowercase(Locale.ROOT).trimEnd('.')

        internal fun isOfficialHost(host: String): Boolean {
            val normalized = canonicalHost(host)
            return listOf("bitwarden.com", "bitwarden.eu", "bitwarden.pw", "bitwarden-gov.com")
                .any { normalized == it || normalized.endsWith(".$it") }
        }

        private fun verifyWith(manager: X509TrustManager, chain: Array<X509Certificate>, authType: String, host: String?): List<X509Certificate> {
            if (host != null) {
                // Android returns the validated chain; standard JVM providers lack this extension.
                val android = try { X509TrustManagerExtensions(manager) } catch (_: IllegalArgumentException) { null }
                if (android != null) return android.checkServerTrusted(chain, authType, host)
            }
            manager.checkServerTrusted(chain, authType)
            return chain.toList()
        }
    }
}

/** AndroidCAStore distinguishes user-added roots from system roots with the user: alias prefix. */
private fun androidUserCaTrustManager(): X509TrustManager? {
    val android = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    val aliases = android.aliases()
    while (aliases.hasMoreElements()) {
        val alias = aliases.nextElement()
        if (alias.startsWith("user:")) {
            val certificate = android.getCertificate(alias) as? X509Certificate ?: continue
            roots.setCertificateEntry(alias, certificate)
        }
    }
    if (roots.size() == 0) return null
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
}

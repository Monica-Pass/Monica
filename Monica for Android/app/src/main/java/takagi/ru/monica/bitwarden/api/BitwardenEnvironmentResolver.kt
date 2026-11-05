package takagi.ru.monica.bitwarden.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale

/** Pure configuration resolution. Certificate parsing belongs off the UI thread. */
object BitwardenEnvironmentResolver {
    private val revisionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val endpointFields = setOf(BitwardenEnvironmentField.BASE, BitwardenEnvironmentField.WEB,
        BitwardenEnvironmentField.API, BitwardenEnvironmentField.IDENTITY, BitwardenEnvironmentField.NOTIFICATIONS)
    private val pemPattern = Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----")

    fun resolve(draft: BitwardenEnvironmentDraft, revision: String): BitwardenValidation<BitwardenEnvironment> {
        val errors = mutableListOf<BitwardenEnvironmentError>()
        if (!revisionPattern.matches(revision)) {
            errors += BitwardenEnvironmentError(BitwardenEnvironmentField.REVISION, BitwardenEnvironmentErrorCode.INVALID_REVISION)
        }
        val region = if (draft.preset == BitwardenEnvironmentPreset.EU) "eu" else "com"
        val baseInput = if (draft.preset == BitwardenEnvironmentPreset.CUSTOM) draft.baseUrl else "https://vault.bitwarden.$region/"
        fun endpoint(raw: String?, field: BitwardenEnvironmentField): HttpUrl? {
            if (raw == null) return null // A failed parent Base already has its own error.
            val result = validateEndpoint(raw, field)
            errors += result.errors
            return result.value
        }
        val base = endpoint(baseInput, BitwardenEnvironmentField.BASE)
        fun service(overrideUrl: String?, field: BitwardenEnvironmentField, suffix: String, officialHost: String): HttpUrl? {
            val raw = overrideUrl?.takeUnless { it.isBlank() }
                ?: if (draft.preset == BitwardenEnvironmentPreset.CUSTOM) base?.resolve(suffix)?.toString()
                else "https://$officialHost.bitwarden.$region/"
            return endpoint(raw, field)
        }
        val webExplicit = !draft.webUrl.isNullOrBlank()
        val web = endpoint(draft.webUrl?.takeUnless { it.isBlank() } ?: base?.toString(), BitwardenEnvironmentField.WEB)
        val api = service(draft.apiUrl, BitwardenEnvironmentField.API, "api/", "api")
        val identity = service(draft.identityUrl, BitwardenEnvironmentField.IDENTITY, "identity/", "identity")
        val notifications = service(draft.notificationsUrl, BitwardenEnvironmentField.NOTIFICATIONS, "notifications/", "notifications")
        errors += BitwardenTransportPolicy.validateHeaders(draft.headers)
        errors += validateTls(draft.tls)
        if (draft.tls.mtlsEnabled) {
            listOf(BitwardenEnvironmentField.BASE to base, BitwardenEnvironmentField.API to api,
                BitwardenEnvironmentField.IDENTITY to identity, BitwardenEnvironmentField.NOTIFICATIONS to notifications)
                .forEach { (field, url) ->
                    if (url != null && !url.isHttps) errors += BitwardenEnvironmentError(field, BitwardenEnvironmentErrorCode.HTTPS_REQUIRED)
                }
        }
        if (errors.isNotEmpty()) return BitwardenValidation(null, errors)
        check(base != null && web != null && api != null && identity != null && notifications != null)
        val headers = draft.headers.filterNot { it.name.isEmpty() && it.value.isEmpty() }
            .map { BitwardenHeader(it.name, it.value) }
        val resolved = BitwardenEnvironment(base, web, api, identity, notifications, draft.preset,
            webExplicit, headers, draft.tls, revision, "")
        return BitwardenValidation(BitwardenEnvironment(base, web, api, identity, notifications, draft.preset,
            webExplicit, headers, draft.tls, revision, fingerprint(resolved)), emptyList())
    }

    fun resolveLegacy(snapshot: BitwardenLegacyEnvironmentSnapshot, revision: String): BitwardenValidation<BitwardenEnvironment> {
        val base = validateEndpoint(snapshot.serverUrl, BitwardenEnvironmentField.BASE)
        val url = base.value ?: return BitwardenValidation(null, base.errors)
        val preset = if (url.isHttps && url.port == 443 && url.encodedPath == "/") {
            when (url.host) {
                "vault.bitwarden.com", "api.bitwarden.com", "identity.bitwarden.com" -> BitwardenEnvironmentPreset.US
                "vault.bitwarden.eu", "api.bitwarden.eu", "identity.bitwarden.eu" -> BitwardenEnvironmentPreset.EU
                else -> BitwardenEnvironmentPreset.CUSTOM
            }
        } else BitwardenEnvironmentPreset.CUSTOM
        return resolve(BitwardenEnvironmentDraft(preset = preset, baseUrl = snapshot.serverUrl,
            apiUrl = snapshot.apiUrl, identityUrl = snapshot.identityUrl, tls = snapshot.tls), revision)
    }

    fun validateEndpoint(
        rawUrl: String,
        field: BitwardenEnvironmentField,
        requireHttps: Boolean = false,
    ): BitwardenValidation<HttpUrl> {
        require(field in endpointFields) { "Expected an endpoint field" }
        val parsed = BitwardenEnvironmentUrlParser.parse(rawUrl, field, allowQuery = false, allowAmbiguousPath = false)
        val url = parsed.value?.url ?: return BitwardenValidation(null, parsed.errors)
        if (requireHttps && !url.isHttps) {
            return BitwardenValidation(null, listOf(BitwardenEnvironmentError(field, BitwardenEnvironmentErrorCode.HTTPS_REQUIRED)))
        }
        val base = if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
        return BitwardenValidation(base, emptyList())
    }

    private fun validateTls(tls: BitwardenTlsConfig): List<BitwardenEnvironmentError> {
        val errors = mutableListOf<BitwardenEnvironmentError>()
        fun error(field: BitwardenEnvironmentField, code: BitwardenEnvironmentErrorCode) { errors += BitwardenEnvironmentError(field, code) }
        tls.caCertificatePem?.let { pem ->
            val field = BitwardenEnvironmentField.TLS_CA
            if (pem.length > BitwardenTransportPolicy.MAX_CA_CERTIFICATE_BYTES) {
                error(field, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
            } else {
                val bytes = pem.toByteArray(Charsets.UTF_8)
                try {
                    if (bytes.size > BitwardenTransportPolicy.MAX_CA_CERTIFICATE_BYTES) {
                        error(field, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
                    } else {
                        validateCaCertificates(pem, CertificateFactory.getInstance("X.509"))
                    }
                } catch (_: Exception) {
                    error(field, BitwardenEnvironmentErrorCode.INVALID_CA_CERTIFICATE)
                } finally { bytes.fill(0) }
            }
        }
        val encoded = tls.clientCertPkcs12Base64
        if (encoded.isNullOrBlank()) {
            if (tls.mtlsEnabled) error(BitwardenEnvironmentField.TLS_CLIENT_CERTIFICATE, BitwardenEnvironmentErrorCode.CLIENT_CERTIFICATE_REQUIRED)
            return errors
        }
        val field = BitwardenEnvironmentField.TLS_CLIENT_CERTIFICATE
        // Bound input before removing legal Base64 line wrapping or allocating decoded bytes.
        if (encoded.length > BitwardenTransportPolicy.MAX_CLIENT_CERTIFICATE_BYTES * 2) {
            error(field, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
            return errors
        }
        var bytes: ByteArray? = null
        val password = tls.clientCertPassword.orEmpty().toCharArray()
        try {
            val compact = encoded.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
            bytes = Base64.getDecoder().decode(compact)
            if (bytes.size > BitwardenTransportPolicy.MAX_CLIENT_CERTIFICATE_BYTES) {
                error(field, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
            } else {
                val store = KeyStore.getInstance("PKCS12")
                store.load(ByteArrayInputStream(bytes), password)
                val aliases = store.aliases()
                var hasPrivateKey = false
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    if (store.isKeyEntry(alias) && store.getKey(alias, password) is PrivateKey) {
                        val chain = store.getCertificateChain(alias)
                        if (!chain.isNullOrEmpty() && chain.all { it is X509Certificate }) hasPrivateKey = true
                    }
                }
                if (!hasPrivateKey) error(field, BitwardenEnvironmentErrorCode.CLIENT_PRIVATE_KEY_REQUIRED)
            }
        } catch (_: Exception) {
            error(field, BitwardenEnvironmentErrorCode.INVALID_CLIENT_CERTIFICATE)
        } finally {
            bytes?.fill(0)
            password.fill('\u0000')
        }
        return errors
    }

    /** Validate every PEM block independently; collection cardinality is not a framing check. */
    internal fun validateCaCertificates(pem: String, factory: CertificateFactory) {
        val blocks = pemPattern.findAll(pem).toList()
        require(blocks.isNotEmpty() && pemPattern.replace(pem, "").isBlank())
        blocks.forEach { block ->
            val bytes = block.value.toByteArray(Charsets.UTF_8)
            try {
                val input = ByteArrayInputStream(bytes)
                require(factory.generateCertificate(input) is X509Certificate)
                require(input.available() == 0)
            } finally { bytes.fill(0) }
        }
    }

    private fun fingerprint(environment: BitwardenEnvironment): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) try { digest.update(bytes) } finally { bytes.fill(0) }
        }
        frame("monica-bitwarden-environment-v1")
        listOf(environment.base, environment.web, environment.api, environment.identity, environment.notifications).forEach { frame(it.toString()) }
        frame(environment.preset.name)
        frame(environment.webExplicit.toString())
        frame(BitwardenTransportPolicy.referer(environment))
        frame(BitwardenTransportPolicy.sendUrlPrefix(environment))
        frame(environment.headers.size.toString())
        environment.headers.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach {
            frame(it.name.lowercase(Locale.ROOT)); frame(it.value)
        }
        with(environment.tls) {
            frame(certificateAlias); frame(caCertificatePem); frame(mtlsEnabled.toString())
            frame(clientCertPkcs12Base64); frame(clientCertPassword)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

/** Shared raw syntax check; transfer queries can be secret, so parsing results are redacted. */
internal object BitwardenEnvironmentUrlParser {
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    internal class Parsed(val url: HttpUrl, val unambiguousPath: Boolean) {
        override fun toString(): String = "ParsedBitwardenUrl(redacted)"
    }

    fun parse(
        raw: String,
        field: BitwardenEnvironmentField,
        allowQuery: Boolean,
        allowAmbiguousPath: Boolean,
    ): BitwardenValidation<Parsed> {
        fun invalid(code: BitwardenEnvironmentErrorCode) = BitwardenValidation<Parsed>(null, listOf(BitwardenEnvironmentError(field, code)))
        if (raw.isEmpty()) return invalid(BitwardenEnvironmentErrorCode.REQUIRED)
        if (raw.any { it.code <= 0x20 || it.code in 0x7f..0x9f || it.isWhitespace() }) {
            return invalid(BitwardenEnvironmentErrorCode.CONTROL_OR_WHITESPACE)
        }
        if ('\\' in raw) return invalid(BitwardenEnvironmentErrorCode.UNSAFE_PATH)
        val schemeMatch = schemePattern.find(raw) ?: return invalid(BitwardenEnvironmentErrorCode.INVALID_URL)
        val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return invalid(BitwardenEnvironmentErrorCode.UNSUPPORTED_SCHEME)
        val authorityStart = schemeMatch.range.last + 1
        val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it == -1) raw.length else it }
        val authority = raw.substring(authorityStart, authorityEnd)
        if ('@' in authority) return invalid(BitwardenEnvironmentErrorCode.USERINFO_FORBIDDEN)
        if (!validAuthority(authority)) return invalid(BitwardenEnvironmentErrorCode.INVALID_URL)
        if ('#' in raw) return invalid(BitwardenEnvironmentErrorCode.FRAGMENT_FORBIDDEN)
        if (!allowQuery && '?' in raw) return invalid(BitwardenEnvironmentErrorCode.QUERY_FORBIDDEN)
        val pathEnd = raw.indexOf('?', authorityEnd).let { if (it == -1) raw.length else it }
        val rawPath = raw.substring(authorityEnd, pathEnd)
        val pathState = pathSafety(rawPath)
        if (pathState == PathSafety.MALFORMED) return invalid(BitwardenEnvironmentErrorCode.INVALID_URL)
        val unambiguous = pathState == PathSafety.SAFE
        if (!allowAmbiguousPath && !unambiguous) return invalid(BitwardenEnvironmentErrorCode.UNSAFE_PATH)
        val url = raw.toHttpUrlOrNull() ?: return invalid(BitwardenEnvironmentErrorCode.INVALID_URL)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return invalid(BitwardenEnvironmentErrorCode.USERINFO_FORBIDDEN)
        return BitwardenValidation(Parsed(url, unambiguous), emptyList())
    }

    private fun validAuthority(authority: String): Boolean {
        if (authority.isEmpty()) return false
        val portText = if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end <= 1) return false
            val tail = authority.substring(end + 1)
            if (tail.isEmpty()) return true
            if (!tail.startsWith(':')) return false
            tail.substring(1)
        } else {
            if (authority.count { it == ':' } > 1 || '[' in authority || ']' in authority) return false
            val colon = authority.indexOf(':')
            if (colon == -1) return true
            if (colon == 0) return false
            authority.substring(colon + 1)
        }
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return false
        val port = portText.toIntOrNull() ?: return false
        return port in 1..65535
    }

    private enum class PathSafety { SAFE, AMBIGUOUS, MALFORMED }

    private fun pathSafety(path: String): PathSafety {
        var ambiguous = false
        for (segment in path.split('/')) {
            val decoded = StringBuilder()
            var index = 0
            while (index < segment.length) {
                val character = segment[index]
                if (character == '%') {
                    if (index + 2 >= segment.length) return PathSafety.MALFORMED
                    val high = segment[index + 1].digitToIntOrNull(16) ?: return PathSafety.MALFORMED
                    val low = segment[index + 2].digitToIntOrNull(16) ?: return PathSafety.MALFORMED
                    val byte = high * 16 + low
                    if (byte == 0x2f || byte == 0x5c || byte < 0x20 || byte == 0x7f) ambiguous = true
                    decoded.append(byte.toChar())
                    index += 3
                } else {
                    decoded.append(character)
                    index++
                }
            }
            if (decoded.toString() == "." || decoded.toString() == "..") ambiguous = true
        }
        return if (ambiguous) PathSafety.AMBIGUOUS else PathSafety.SAFE
    }
}

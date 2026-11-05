package takagi.ru.monica.bitwarden.api

import okhttp3.HttpUrl
import java.util.Collections
import java.util.Locale

/** Routing policy only. A valid destination never replaces a current account/attempt lease. */
object BitwardenTransportPolicy {
    const val MAX_HEADERS: Int = 16
    const val MAX_HEADER_NAME_BYTES: Int = 128
    const val MAX_HEADER_VALUE_BYTES: Int = 8 * 1024
    const val MAX_TOTAL_HEADER_BYTES: Int = 32 * 1024
    const val MAX_CLIENT_CERTIFICATE_BYTES: Int = 1024 * 1024
    const val MAX_CA_CERTIFICATE_BYTES: Int = 256 * 1024

    private val reservedHeaders = setOf(
        "authorization", "proxy-authorization", "proxy-authenticate", "host", "cookie", "set-cookie",
        "referer", "origin", "user-agent", "content-type", "content-length", "content-encoding",
        "transfer-encoding", "connection", "keep-alive", "te", "trailer", "upgrade", "accept",
        "accept-encoding", "accept-language", "cache-control", "auth-email", "device-type",
        "keyguard-client", "bitwarden-client-name", "bitwarden-client-version",
    )
    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

    fun isReservedHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in reservedHeaders || lower.startsWith("sec-")
    }

    fun validateHeaders(headers: List<BitwardenHeaderDraft>): List<BitwardenEnvironmentError> {
        if (headers.size > MAX_HEADERS) {
            return listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.HEADERS, BitwardenEnvironmentErrorCode.TOO_MANY_HEADERS))
        }
        val errors = mutableListOf<BitwardenEnvironmentError>()
        val names = mutableSetOf<String>()
        var total = 0L
        headers.forEachIndexed { index, header ->
            if (header.name.isEmpty() && header.value.isEmpty()) return@forEachIndexed
            fun error(field: BitwardenEnvironmentField, code: BitwardenEnvironmentErrorCode) {
                errors += BitwardenEnvironmentError(field, code, index)
            }
            val validName = header.name.isNotEmpty() && header.name.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS
            }
            if (!validName) error(BitwardenEnvironmentField.HEADER_NAME, BitwardenEnvironmentErrorCode.INVALID_HEADER_NAME)
            else {
                if (isReservedHeader(header.name)) error(BitwardenEnvironmentField.HEADER_NAME, BitwardenEnvironmentErrorCode.RESERVED_HEADER)
                if (!names.add(header.name.lowercase(Locale.ROOT))) error(BitwardenEnvironmentField.HEADER_NAME, BitwardenEnvironmentErrorCode.DUPLICATE_HEADER)
            }
            if (header.name.length > MAX_HEADER_NAME_BYTES) error(BitwardenEnvironmentField.HEADER_NAME, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
            if (header.value.any { it.code !in 0x20..0x7e }) error(BitwardenEnvironmentField.HEADER_VALUE, BitwardenEnvironmentErrorCode.INVALID_HEADER_VALUE)
            if (header.value.length > MAX_HEADER_VALUE_BYTES) error(BitwardenEnvironmentField.HEADER_VALUE, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE)
            // Valid names/values are ASCII; invalid Unicode already prevents publication.
            total += header.name.length.toLong() + header.value.length
        }
        if (total > MAX_TOTAL_HEADER_BYTES) errors += BitwardenEnvironmentError(BitwardenEnvironmentField.HEADERS, BitwardenEnvironmentErrorCode.TOTAL_HEADERS_TOO_LARGE)
        return Collections.unmodifiableList(errors)
    }

    fun canUseAccountTransport(environment: BitwardenEnvironment, rawUrl: String): Boolean {
        val parsed = BitwardenEnvironmentUrlParser.parse(rawUrl, BitwardenEnvironmentField.TRANSFER_URL,
            allowQuery = true, allowAmbiguousPath = false).value ?: return false
        return inAccountScope(environment, parsed.url)
    }

    fun validateTransferUrl(environment: BitwardenEnvironment, rawUrl: String): BitwardenValidation<BitwardenTransferTarget> {
        val parsed = BitwardenEnvironmentUrlParser.parse(rawUrl, BitwardenEnvironmentField.TRANSFER_URL,
            allowQuery = true, allowAmbiguousPath = true)
        val target = parsed.value ?: return BitwardenValidation(null, parsed.errors)
        val account = target.unambiguousPath && inAccountScope(environment, target.url)
        if (!account && !target.url.isHttps) {
            return BitwardenValidation(null, listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.TRANSFER_URL,
                BitwardenEnvironmentErrorCode.HTTPS_REQUIRED)))
        }
        return BitwardenValidation(BitwardenTransferTarget(target.url, account), emptyList())
    }

    fun referer(environment: BitwardenEnvironment): String? =
        environment.base.toString().takeIf { environment.preset == BitwardenEnvironmentPreset.CUSTOM }

    fun notificationHubUrl(environment: BitwardenEnvironment): HttpUrl = checkNotNull(environment.notifications.resolve("hub"))

    fun sendUrlPrefix(environment: BitwardenEnvironment): String = when {
        environment.webExplicit || environment.preset == BitwardenEnvironmentPreset.CUSTOM -> "${environment.web}#/send/"
        environment.preset == BitwardenEnvironmentPreset.EU -> "https://send.bitwarden.eu/#/send/"
        else -> "https://send.bitwarden.com/#/send/"
    }

    private fun inAccountScope(environment: BitwardenEnvironment, url: HttpUrl): Boolean {
        if (environment.tls.mtlsEnabled && !url.isHttps) return false
        return listOf(environment.base, environment.api, environment.identity, environment.notifications).any { base ->
            base.scheme == url.scheme && base.host == url.host && base.port == url.port &&
                (url.encodedPath == base.encodedPath.trimEnd('/') || url.encodedPath.startsWith(base.encodedPath))
        }
    }
}

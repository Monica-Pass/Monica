package takagi.ru.monica.bitwarden.service

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

enum class BitwardenSendReceiverFailure {
    INVALID_LINK, INVALID_TARGET, TARGET_REVIEW_REQUIRED, INVALID_INPUT, BUSY, CLOSED,
    UNSUPPORTED, UNAVAILABLE, REJECTED, RATE_LIMITED, NETWORK, OUTCOME_UNKNOWN,
    INVALID_RESPONSE, WRONG_KEY_OR_DAMAGED, TOO_LARGE, TOKEN_EXPIRED, IO,
}

/** Codes only. Never attach a URL, server body, credential or underlying throwable. */
class BitwardenSendReceiverException(val code: BitwardenSendReceiverFailure) : Exception(code.name)

internal fun receiverRequire(value: Boolean, code: BitwardenSendReceiverFailure) {
    if (!value) throw BitwardenSendReceiverException(code)
}

/** Public endpoints only: this type cannot carry any account or transport credential. */
class BitwardenSendReceiverTarget internal constructor(
    val webBase: HttpUrl,
    val apiBase: HttpUrl,
    val identityBase: HttpUrl,
) {
    internal fun sameAs(other: BitwardenSendReceiverTarget): Boolean =
        webBase == other.webBase && apiBase == other.apiBase && identityBase == other.identityBase
    override fun toString(): String = "BitwardenSendReceiverTarget(redacted)"
}

/** One-use, in-memory link. A session consumes its key; callers must close unused links. */
class BitwardenSendReceiverLink private constructor(
    val webBase: HttpUrl,
    val suggestedApiBase: HttpUrl,
    val suggestedIdentityBase: HttpUrl,
    val requiresTargetReview: Boolean,
    internal val accessId: String,
    private var material: ByteArray?,
) : AutoCloseable {
    fun target(
        apiBase: String = suggestedApiBase.toString(),
        identityBase: String = suggestedIdentityBase.toString(),
        reviewed: Boolean = false,
    ): BitwardenSendReceiverTarget {
        receiverRequire(!requiresTargetReview || reviewed, BitwardenSendReceiverFailure.TARGET_REVIEW_REQUIRED)
        val api = receiverBaseUrl(apiBase)
        val identity = receiverBaseUrl(identityBase)
        receiverRequire(requiresTargetReview || (api == suggestedApiBase && identity == suggestedIdentityBase),
            BitwardenSendReceiverFailure.INVALID_TARGET)
        return BitwardenSendReceiverTarget(webBase, api, identity)
    }

    @Synchronized internal fun consumeKey(): ByteArray {
        val result = material ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)
        material = null
        return result
    }

    @Synchronized override fun close() { material?.fill(0); material = null }
    override fun toString(): String = "BitwardenSendReceiverLink(redacted)"

    companion object {
        fun parse(raw: String): BitwardenSendReceiverLink {
            try {
                receiverRequire(raw.length in 1..8192 && raw.count { it == '#' } == 1,
                    BitwardenSendReceiverFailure.INVALID_LINK)
                val (base, fragment) = raw.split('#', limit = 2)
                val web = receiverBaseUrl(base)
                val officialRegion = when (web.host) {
                    "send.bitwarden.com", "vault.bitwarden.com" -> "com"
                    "send.bitwarden.eu", "vault.bitwarden.eu" -> "eu"
                    else -> null
                }
                receiverRequire(officialRegion == null || (web.port == 443 && web.encodedPath == "/"),
                    BitwardenSendReceiverFailure.INVALID_LINK)
                val segments = when {
                    fragment.startsWith("/send/") -> fragment.removePrefix("/send/").split('/')
                    officialRegion != null && web.host.startsWith("send.") -> fragment.split('/')
                    else -> throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_LINK)
                }
                receiverRequire(segments.size == 2, BitwardenSendReceiverFailure.INVALID_LINK)
                val idBytes = receiverUrlKey(segments[0])
                idBytes.fill(0)
                val material = receiverUrlKey(segments[1])
                val api = if (officialRegion != null) "https://api.bitwarden.$officialRegion/".toHttpUrlOrNull()!!
                    else web.newBuilder().addPathSegment("api").addPathSegment("").build()
                val identity = if (officialRegion != null) "https://identity.bitwarden.$officialRegion/".toHttpUrlOrNull()!!
                    else web.newBuilder().addPathSegment("identity").addPathSegment("").build()
                return BitwardenSendReceiverLink(web, api, identity, officialRegion == null, segments[0], material)
            } catch (_: Exception) {
                throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_LINK)
            }
        }
    }
}

internal fun receiverUrlKey(raw: String): ByteArray {
    receiverRequire(raw.length == 22 && raw.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' },
        BitwardenSendReceiverFailure.INVALID_LINK)
    val bytes = try { Base64.getUrlDecoder().decode(raw) } catch (_: Exception) {
        throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_LINK)
    }
    if (bytes.size != 16 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != raw) {
        bytes.fill(0)
        throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_LINK)
    }
    return bytes
}

/** Validate raw spelling before HttpUrl can normalize away dot segments or credentials. */
internal fun receiverBaseUrl(raw: String): HttpUrl {
    val url = receiverSafeUrl(raw, allowQuery = false)
    return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
}

internal fun receiverSafeUrl(raw: String, allowQuery: Boolean): HttpUrl {
    receiverRequire(raw.length in 1..8192 && raw.none { it.code <= 0x20 || it.code in 0x7f..0x9f || it.isWhitespace() } &&
        '\\' !in raw && '#' !in raw && (allowQuery || '?' !in raw), BitwardenSendReceiverFailure.INVALID_TARGET)
    receiverRequire(raw.startsWith("https://", ignoreCase = true), BitwardenSendReceiverFailure.INVALID_TARGET)
    val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?')
    receiverRequire(authority.isNotBlank() && '@' !in authority, BitwardenSendReceiverFailure.INVALID_TARGET)
    val path = raw.substringAfter("://").substringAfter('/', "").substringBefore('?')
    receiverRequire(!path.contains("//"), BitwardenSendReceiverFailure.INVALID_TARGET)
    for (segment in path.split('/')) {
        // Percent escapes are allowed for ordinary path bytes, never nested encodings/separators/dots.
        val decoded = StringBuilder()
        var index = 0
        while (index < segment.length) {
            val ch = segment[index]
            if (ch == '%') {
                receiverRequire(index + 2 < segment.length, BitwardenSendReceiverFailure.INVALID_TARGET)
                val value = segment.substring(index + 1, index + 3).toIntOrNull(16)
                    ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_TARGET)
                receiverRequire(value !in 0..0x20 && value !in 0x7f..0x9f && value !in listOf(37, 47, 92),
                    BitwardenSendReceiverFailure.INVALID_TARGET)
                decoded.append(value.toChar()); index += 3
            } else { decoded.append(ch); index++ }
        }
        receiverRequire(decoded.toString() !in listOf(".", ".."), BitwardenSendReceiverFailure.INVALID_TARGET)
    }
    val url = raw.toHttpUrlOrNull() ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_TARGET)
    receiverRequire(url.isHttps && url.username.isEmpty() && url.password.isEmpty(), BitwardenSendReceiverFailure.INVALID_TARGET)
    return url
}

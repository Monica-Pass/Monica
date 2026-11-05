package takagi.ru.monica.bitwarden.api

import okhttp3.HttpUrl
import java.util.Collections

enum class BitwardenEnvironmentPreset { US, EU, CUSTOM }

class BitwardenHeaderDraft(val name: String, val value: String) {
    override fun toString(): String = "BitwardenHeaderDraft(redacted)"
}

/** Memory-only input; contains credentials and must never enter saved instance state. */
class BitwardenEnvironmentDraft(
    val preset: BitwardenEnvironmentPreset,
    val baseUrl: String = "",
    val webUrl: String? = null,
    val apiUrl: String? = null,
    val identityUrl: String? = null,
    val notificationsUrl: String? = null,
    headers: List<BitwardenHeaderDraft> = emptyList(),
    tls: BitwardenTlsConfig = BitwardenTlsConfig(),
) {
    val headers: List<BitwardenHeaderDraft> = Collections.unmodifiableList(ArrayList(headers))
    val tls: BitwardenTlsConfig = tls.copy()
    override fun toString(): String = "BitwardenEnvironmentDraft(redacted)"
}

/** Old persisted columns only. A missing new protected payload must not use this fallback. */
class BitwardenLegacyEnvironmentSnapshot(
    val serverUrl: String,
    val apiUrl: String? = null,
    val identityUrl: String? = null,
    tls: BitwardenTlsConfig = BitwardenTlsConfig(),
) {
    val tls: BitwardenTlsConfig = tls.copy()
    override fun toString(): String = "BitwardenLegacyEnvironmentSnapshot(redacted)"
}

class BitwardenHeader internal constructor(val name: String, val value: String) {
    override fun toString(): String = "BitwardenHeader(redacted)"
}

/** Construct only after resolution; account identity and active leases belong to the caller. */
class BitwardenEnvironment internal constructor(
    val base: HttpUrl,
    val web: HttpUrl,
    val api: HttpUrl,
    val identity: HttpUrl,
    val notifications: HttpUrl,
    val preset: BitwardenEnvironmentPreset,
    val webExplicit: Boolean,
    headers: List<BitwardenHeader>,
    tls: BitwardenTlsConfig,
    val revision: String,
    internal val fingerprint: String,
) {
    val headers: List<BitwardenHeader> = Collections.unmodifiableList(ArrayList(headers))
    val tls: BitwardenTlsConfig = tls.copy()
    override fun toString(): String = "BitwardenEnvironment(redacted)"
}

enum class BitwardenEnvironmentField {
    BASE, WEB, API, IDENTITY, NOTIFICATIONS,
    HEADERS, HEADER_NAME, HEADER_VALUE,
    TLS_CA, TLS_CLIENT_CERTIFICATE, TLS_CLIENT_PASSWORD, TLS_MODE,
    REVISION, TRANSFER_URL,
}

enum class BitwardenEnvironmentErrorCode {
    REQUIRED, INVALID_URL, UNSUPPORTED_SCHEME, HTTPS_REQUIRED,
    USERINFO_FORBIDDEN, QUERY_FORBIDDEN, FRAGMENT_FORBIDDEN,
    CONTROL_OR_WHITESPACE, UNSAFE_PATH, INVALID_REVISION,
    INVALID_HEADER_NAME, INVALID_HEADER_VALUE, RESERVED_HEADER,
    DUPLICATE_HEADER, TOO_MANY_HEADERS, VALUE_TOO_LARGE,
    TOTAL_HEADERS_TOO_LARGE,
    INVALID_CA_CERTIFICATE, CLIENT_CERTIFICATE_REQUIRED,
    INVALID_CLIENT_CERTIFICATE, CLIENT_PRIVATE_KEY_REQUIRED,
}

/** Codes only: never retain input, a server message or a throwable carrying credentials. */
data class BitwardenEnvironmentError(
    val field: BitwardenEnvironmentField,
    val code: BitwardenEnvironmentErrorCode,
    val index: Int? = null,
) {
    init { require(index == null || index >= 0) }
}

class BitwardenValidation<out T : Any> internal constructor(
    val value: T?,
    errors: List<BitwardenEnvironmentError>,
) {
    val errors: List<BitwardenEnvironmentError> = Collections.unmodifiableList(ArrayList(errors))
    init { require((value != null) == this.errors.isEmpty()) }
    val isValid: Boolean get() = value != null
    override fun toString(): String = if (isValid) "BitwardenValidation(valid)" else "BitwardenValidation(errors=$errors)"
}

/** The decision retains raw-path validation that HttpUrl normalization could otherwise erase. */
class BitwardenTransferTarget internal constructor(
    val url: HttpUrl,
    val useAccountTransport: Boolean,
) {
    override fun toString(): String = "BitwardenTransferTarget(redacted)"
}

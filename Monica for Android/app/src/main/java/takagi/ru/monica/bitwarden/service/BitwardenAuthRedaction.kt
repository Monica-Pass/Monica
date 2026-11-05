package takagi.ru.monica.bitwarden.service

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException

/** The fixed phrases preserve the existing localized Repository error mapping. */
internal enum class BitwardenAuthResponseKind(val safePhrase: String) {
    BAD_LOGIN("invalid_username_or_password"),
    NEW_DEVICE_CODE("Invalid New Device OTP"),
    TWO_FACTOR_CODE("Two-step token is invalid"),
    NEW_DEVICE_REQUIRED("New device verification required"),
    CAPTCHA_REQUIRED("captcha required"),
    CAPTCHA("captcha validation failed"),
    KDF_MEMORY("Bitwarden Argon2id KDF requires available memory"),
    LOCKED("too many attempts"),
    INVALID_GRANT("invalid_grant"),
    UNKNOWN("Authentication request failed"),
}

internal fun classifyBitwardenAuthResponse(raw: String?): BitwardenAuthResponseKind {
    fun has(value: String) = raw?.contains(value, ignoreCase = true) == true
    return when {
        has("invalid_username_or_password") || has("Username or password is incorrect") -> BitwardenAuthResponseKind.BAD_LOGIN
        has("Invalid New Device OTP") -> BitwardenAuthResponseKind.NEW_DEVICE_CODE
        has("Two-step token is invalid") || has("invalid two-step") -> BitwardenAuthResponseKind.TWO_FACTOR_CODE
        has("new device verification") -> BitwardenAuthResponseKind.NEW_DEVICE_REQUIRED
        has("captcha required") && !has("sitekey") -> BitwardenAuthResponseKind.CAPTCHA_REQUIRED
        has("captcha") -> BitwardenAuthResponseKind.CAPTCHA
        has("Bitwarden Argon2id KDF memory is too high") || has("Bitwarden Argon2id KDF requires") ||
            has("ARGON2_MEMORY_ALLOCATION_ERROR") || has("ARGON2JNI_MALLOC_FAILED") -> BitwardenAuthResponseKind.KDF_MEMORY
        has("locked") || has("too many attempts") -> BitwardenAuthResponseKind.LOCKED
        has("invalid_grant") -> BitwardenAuthResponseKind.INVALID_GRANT
        else -> BitwardenAuthResponseKind.UNKNOWN
    }
}

internal fun redactedBitwardenAuthResponse(status: Int, raw: String? = null): Exception {
    val kind = classifyBitwardenAuthResponse(raw)
    val safeStatus = status.takeIf { it in 100..599 }
    return Exception("${kind.safePhrase}; HTTP=${safeStatus ?: 0}")
}

/** Rebuild typed transport failures without retaining the server text, URL, cause or suppressed data. */
internal fun redactedBitwardenAuthException(error: Exception): Exception {
    if (error is CancellationException) throw error
    return when (classifyBitwardenLoginError(error)) {
        BitwardenLoginError.CertificateExpired -> CertificateExpiredException("Certificate expired")
        BitwardenLoginError.CertificateNotYetValid -> CertificateNotYetValidException("Certificate not yet valid")
        BitwardenLoginError.CertificateHostnameMismatch -> SSLPeerUnverifiedException("Hostname mismatch")
        BitwardenLoginError.CertificateUntrusted -> CertificateException("Certificate untrusted")
        BitwardenLoginError.TlsHandshake -> SSLHandshakeException("TLS handshake failed")
        BitwardenLoginError.Dns -> UnknownHostException("DNS lookup failed")
        BitwardenLoginError.Timeout -> SocketTimeoutException("Connection timeout")
        BitwardenLoginError.Connection -> ConnectException("Connection failed")
        BitwardenLoginError.Proxy -> IOException("Proxy authentication required")
        BitwardenLoginError.CleartextBlocked -> UnknownServiceException("Cleartext traffic not permitted")
        BitwardenLoginError.NetworkIo -> IOException("Network I/O failed")
        null -> Exception(classifyBitwardenAuthResponse(error.message).safePhrase)
    }
}

internal fun bitwardenAuthDiagnosticLine(
    flow: String,
    attemptId: String,
    stage: String,
    httpStatus: Int? = null,
    failureKind: BitwardenLoginError? = null,
): String {
    val safeFlow = flow.takeIf { it in authFlows } ?: "unknown"
    val safeStage = stage.takeIf { it in authStages } ?: "unknown"
    val safeAttempt = attemptId.takeIf { it.matches(Regex("[0-9a-f]{8}")) } ?: "unknown"
    return buildString {
        append("[BW_DIAG] flow=$safeFlow attempt=$safeAttempt stage=$safeStage")
        httpStatus?.takeIf { it in 100..599 }?.let { append(" code=$it") }
        failureKind?.let { append(" failure=${it.name}") }
    }
}

private val authFlows = setOf("primary", "two_factor", "new_device", "two_factor_email", "refresh")
private val authStages = setOf(
    "start", "token_request", "exception", "prelogin_start", "prelogin_ok", "prelogin_fail",
    "prelogin_exception", "prelogin_normalized", "stop_prelogin_error", "stop_unsupported_kdf",
    "result_two_factor", "result_success", "result_two_factor_from_error", "result_new_device_required",
    "result_captcha_required", "result_error", "retry_keyguard_start", "retry_keyguard_success",
    "retry_keyguard_two_factor", "retry_keyguard_two_factor_from_error", "retry_keyguard_new_device_required",
    "retry_keyguard_captcha_required", "retry_keyguard_error", "retry_keyguard_exception",
    "send_start", "send_success", "send_error", "send_exception",
)

package takagi.ru.monica.ui.scanner

import takagi.ru.monica.util.TotpScanParseResult
import takagi.ru.monica.util.TotpUriParser

internal enum class CredentialQrKind { AUTHENTICATOR, PASSKEY, UNSUPPORTED }

internal object UnifiedQrRouter {
    fun classify(raw: String): CredentialQrKind {
        if (raw.length > 64 * 1024) return CredentialQrKind.UNSUPPORTED
        val value = raw.trim()
        if (value.startsWith("FIDO:/", ignoreCase = true)) {
            return if (FidoQrCode.normalizedOrNull(value) != null) CredentialQrKind.PASSKEY
            else CredentialQrKind.UNSUPPORTED
        }
        return when (TotpUriParser.parseScannedContent(value)) {
            is TotpScanParseResult.Single, is TotpScanParseResult.Multiple -> CredentialQrKind.AUTHENTICATOR
            // Keep the existing actionable migration/PhoneFactor explanation in the OTP route.
            is TotpScanParseResult.MigrationFailure, TotpScanParseResult.UnsupportedPhoneFactor -> CredentialQrKind.AUTHENTICATOR
            TotpScanParseResult.InvalidFormat -> CredentialQrKind.UNSUPPORTED
        }
    }
}

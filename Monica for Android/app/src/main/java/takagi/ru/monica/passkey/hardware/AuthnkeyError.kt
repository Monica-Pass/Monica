// Adapted from Authnkey, Copyright (c) 2025 Michel Le Bihan (MIT).
// See docs/licenses/Authnkey-MIT.txt.
package takagi.ru.monica.passkey.hardware

sealed class AuthnkeyError(message: String) : Exception(message) {
    // Connection errors
    class NotIsoDepTag : AuthnkeyError("Not an ISO-DEP tag")
    class FidoAppletNotFound : AuthnkeyError("FIDO applet not found")
    class ConnectionFailed : AuthnkeyError("Connection failed")
    class NotConnected : AuthnkeyError("Not connected")

    // PIN protocol errors
    class PinProtocolNotInitialized : AuthnkeyError("PIN protocol not initialized")
    class PinProtocolInitFailed : AuthnkeyError("PIN protocol initialization failed")

    // Authentication errors
    class UserVerificationRequiredNoPin : AuthnkeyError("User verification required but no PIN set")
    class PinNotSupportedForOperation : AuthnkeyError("PIN cannot be used for this operation")
    class PinBlocked : AuthnkeyError("PIN is blocked")

    // On-device UV errors
    class UvBlocked : AuthnkeyError("Biometric verification is blocked")
}

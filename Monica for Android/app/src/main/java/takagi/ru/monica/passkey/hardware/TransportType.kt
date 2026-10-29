// Adapted from Authnkey, Copyright (c) 2025 Michel Le Bihan (MIT).
// See docs/licenses/Authnkey-MIT.txt.
package takagi.ru.monica.passkey.hardware

/**
 * A WebAuthn AuthenticatorTransport. Not an enum: the spec types these as plain
 * strings so that values outside the registry can be used without updating every
 * implementation, and it expects unknown ones to be carried rather than rejected.
 */
@JvmInline
value class TransportType private constructor(val value: String) {
    override fun toString() = value

    companion object {
        fun of(raw: String) = TransportType(raw.lowercase())

        val USB = of("usb")
        val NFC = of("nfc")
        val BLE = of("ble")
        val SMART_CARD = of("smart-card")
        val HYBRID = of("hybrid")
        val INTERNAL = of("internal")
    }
}

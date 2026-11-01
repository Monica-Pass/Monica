package takagi.ru.monica.lansync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** A short-lived network hint. Only the enclosed LAN invitation authenticates the peer. */
internal class NearbyHotspotInvitation private constructor(
    val ssid: String,
    val passphrase: String,
    val pairing: String
) {
    val link: String get() = "$PREFIX:${encode(ssid)}:${encode(passphrase)}:${encode(pairing)}"
    override fun toString() = "NearbyHotspotInvitation()"

    companion object {
        private const val PREFIX = "monica-hotspot:1"
        const val MAX_LENGTH = 1100

        fun create(ssid: String, passphrase: String, pairing: String): NearbyHotspotInvitation? {
            if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32 || ssid.any(Char::isISOControl)) return null
            if (passphrase.length !in 8..63 || passphrase.any { it.code !in 32..126 }) return null
            val invitation = LanPairingInvitation.parse(pairing)?.takeIf { it.version == 2 } ?: return null
            return NearbyHotspotInvitation(ssid, passphrase, invitation.link)
        }

        fun parse(text: String): NearbyHotspotInvitation? {
            if (text.length > MAX_LENGTH) return null
            val parts = text.trim().split(':')
            if (parts.size != 5 || parts[0] != "monica-hotspot" || parts[1] != "1") return null
            return create(decode(parts[2], 32) ?: return null,
                decode(parts[3], 63) ?: return null, decode(parts[4], 512) ?: return null)
        }

        private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
        private fun decode(value: String, limit: Int): String? {
            if (value.isEmpty() || value.length > (limit * 4 + 2) / 3 ||
                value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
            val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { return null }
            return try {
                if (bytes.size > limit || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) return null
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { null } finally { bytes.fill(0) }
        }
    }
}

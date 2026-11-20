package takagi.ru.monica.ui.scanner

import org.bouncycastle.asn1.sec.SECNamedCurves
import takagi.ru.monica.passkey.hardware.CborDecoder
import java.io.ByteArrayOutputStream

/** Validates the caBLE v2 QR envelope before handing it to Android; never performs authentication. */
internal object FidoQrCode {
    private const val PREFIX = "FIDO:/"
    private const val MAX_LENGTH = 8192
    private val p256 by lazy { SECNamedCurves.getByName("secp256r1").curve }

    // Encoding reference: Chromium device/fido/cable/v2_handshake.cc, qr::Parse/DigitsToBytes.
    fun normalizedOrNull(raw: String): String? {
        if (raw.length > MAX_LENGTH) return null
        val value = raw.trim()
        if (!value.startsWith(PREFIX, ignoreCase = true)) return null
        return try {
            val digits = value.substring(PREFIX.length)
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
            val bytes = ByteArrayOutputStream()
            digits.chunked(17).forEach { chunk ->
                val count = when (chunk.length) {
                    17 -> 7; 15 -> 6; 13 -> 5; 10 -> 4; 8 -> 3; 5 -> 2; 3 -> 1
                    else -> return null
                }
                val number = chunk.toLongOrNull() ?: return null
                if (number ushr (count * 8) != 0L) return null
                repeat(count) { bytes.write((number ushr (it * 8)).toInt() and 0xff) }
            }
            val fields = CborDecoder.decode(bytes.toByteArray()) as? Map<*, *> ?: return null
            val publicKey = fields[0L] as? ByteArray ?: return null
            val secret = fields[1L] as? ByteArray ?: return null
            if (publicKey.size != 33 || publicKey[0].toInt() !in 2..3 || secret.size != 16) return null
            val point = p256.decodePoint(publicKey)
            if (point.isInfinity || !point.isValid) return null
            if (fields.containsKey(2L) && (fields[2L] as? Long)?.let { it in 0..65535 } != true) return null
            if (fields.containsKey(3L) && (fields[3L] as? Long)?.let { it >= 0 } != true) return null
            if (fields.containsKey(4L) && fields[4L] !is Boolean) return null
            // Digital-credential presentations use the same envelope, but are not passkey requests.
            if (fields.containsKey(5L) && fields[5L] !in setOf("ga", "mc")) return null
            PREFIX + digits
        } catch (_: Exception) {
            null
        }
    }
}

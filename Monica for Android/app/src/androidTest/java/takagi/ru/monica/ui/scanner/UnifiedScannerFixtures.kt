package takagi.ru.monica.ui.scanner

import org.bouncycastle.asn1.sec.SECNamedCurves
import takagi.ru.monica.passkey.hardware.cbor

internal object UnifiedScannerFixtures {
    fun fido(): String {
        val bytes = cbor { map {
            0 to SECNamedCurves.getByName("secp256r1").g.getEncoded(true)
            1 to ByteArray(16) { it.toByte() }
            5 to "ga"
        } }
        return "FIDO:/" + bytes.asList().chunked(7).joinToString("") { chunk ->
            var value = 0L
            chunk.forEachIndexed { i, b -> value = value or ((b.toLong() and 255) shl (8 * i)) }
            value.toString().padStart(intArrayOf(0, 3, 5, 8, 10, 13, 15, 17)[chunk.size], '0')
        }
    }
}

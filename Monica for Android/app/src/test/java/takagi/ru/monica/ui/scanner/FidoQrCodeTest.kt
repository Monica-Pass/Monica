package takagi.ru.monica.ui.scanner

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.passkey.hardware.cbor

class FidoQrCodeTest {
    private val key = SECNamedCurves.getByName("secp256r1").g.getEncoded(true)
    private fun envelope(type: String? = null, secret: ByteArray = ByteArray(16), publicKey: ByteArray = key) = cbor {
        map { 0 to publicKey; 1 to secret; if (type != null) 5 to type }
    }
    private fun qr(bytes: ByteArray): String = "FIDO:/" + bytes.asList().chunked(7).joinToString("") { chunk ->
        var n = 0L
        chunk.forEachIndexed { i, b -> n = n or ((b.toLong() and 255) shl (8 * i)) }
        n.toString().padStart(intArrayOf(0, 3, 5, 8, 10, 13, 15, 17)[chunk.size], '0')
    }

    @Test fun acceptsLegacyAssertionAndCreationEnvelopes() {
        listOf(null, "ga", "mc").forEach { type ->
            val code = qr(envelope(type))
            assertEquals(code, FidoQrCode.normalizedOrNull(code))
            assertEquals(code, FidoQrCode.normalizedOrNull("  " + code.lowercase() + "\n"))
        }
    }

    @Test fun rejectsLinksAndMalformedDecimalEncoding() {
        listOf("https://example.test", "intent://test", "FIDO://123", "FIDO:/", "FIDO:/1", "FIDO:/1234",
            "FIDO:/99999999999999999", "FIDO:/256", "FIDO:/123%30", "FIDO:/" + "0".repeat(8192))
            .forEach { assertNull(it.take(30), FidoQrCode.normalizedOrNull(it)) }
    }

    @Test fun rejectsWrongSecretKeyAndDigitalCredentialTypes() {
        listOf(envelope(secret = ByteArray(15)), envelope(secret = ByteArray(17)),
            envelope(publicKey = byteArrayOf(2) + ByteArray(32) { -1 }),
            envelope(publicKey = ByteArray(33)), envelope("dcp"), envelope("dci"), envelope("GA"))
            .forEach { assertNull(FidoQrCode.normalizedOrNull(qr(it))) }
    }

    @Test fun rejectsTrailingCborAndDuplicateKeys() {
        assertNull(FidoQrCode.normalizedOrNull(qr(envelope() + byteArrayOf(0))))
        assertNull(FidoQrCode.normalizedOrNull(qr(cbor { map { 0 to key; 1 to ByteArray(16); 1 to ByteArray(16) } })))
    }

    @Test fun validatesOptionalKnownFieldsButAllowsExtensions() {
        val valid = cbor { map { 0 to key; 1 to ByteArray(16); 2 to 2; 3 to 123L; 4 to true; 9 to "future" } }
        assertNotNull(FidoQrCode.normalizedOrNull(qr(valid)))
        listOf(2 to -1, 2 to 65536, 3 to -1, 4 to "true", 5 to 2).forEach { (k, v) ->
            assertNull(FidoQrCode.normalizedOrNull(qr(cbor { map { 0 to key; 1 to ByteArray(16); k to v } })))
        }
    }
}

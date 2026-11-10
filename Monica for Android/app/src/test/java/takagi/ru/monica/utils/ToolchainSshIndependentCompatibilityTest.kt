package takagi.ru.monica.utils

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec

/** JDK 17 oracle: no BouncyCastle parser, signer, or key converter is used by this test. */
class ToolchainSshIndependentCompatibilityTest {
    @Test fun rsa2048WireDerSigningFingerprintAndCodec() { verifyRsa(2048) }
    @Test fun rsa3072WireDerSigningFingerprintAndCodec() { verifyRsa(3072) }
    @Test fun rsa4096WireDerSigningFingerprintAndCodec() { verifyRsa(4096) }

    @Test fun ed25519OpenSshSeedSigningFingerprintAndCodec() {
        val data = SshKeyGenerator.generate(SshKeyGenerator.Request.Ed25519, "  synthetic only  ")
        assertEquals(SshKeyData.ALGORITHM_ED25519, data.algorithm)
        assertEquals(256, data.keySize)
        val publicBlob = publicBlobAndCommonContract(data, "ssh-ed25519")
        val publicReader = WireReader(publicBlob)
        assertEquals("ssh-ed25519", publicReader.text())
        val publicBytes = publicReader.field()
        assertEquals(32, publicBytes.size)
        publicReader.requireEnd()

        val privateBlob = pem(data.privateKeyOpenSsh, "OPENSSH PRIVATE KEY")
        val reader = WireReader(privateBlob)
        assertTrue("OpenSSH magic preserved", reader.take(15).contentEquals("openssh-key-v1\u0000".toByteArray()))
        assertEquals("none", reader.text())
        assertEquals("none", reader.text())
        assertEquals(0, reader.field().size)
        assertEquals(1, reader.uint32())
        assertTrue("Outer public blob matches", reader.field().contentEquals(publicBlob))
        val secretBlock = reader.field()
        reader.requireEnd()
        assertEquals(0, secretBlock.size % 8)
        val secretReader = WireReader(secretBlock)
        // Check integers may have the high bit set, so compare their four raw bytes.
        assertTrue("Duplicated check integers match", secretReader.take(4).contentEquals(secretReader.take(4)))
        assertEquals("ssh-ed25519", secretReader.text())
        assertTrue("Inner public key matches", secretReader.field().contentEquals(publicBytes))
        val secretBytes = secretReader.field()
        assertEquals(64, secretBytes.size)
        assertTrue("Private key public suffix matches", secretBytes.copyOfRange(32, 64).contentEquals(publicBytes))
        assertEquals("", secretReader.text())
        var padding = 1
        while (secretReader.remaining > 0) {
            assertEquals("OpenSSH padding byte", padding++, secretReader.take(1)[0].toInt() and 255)
        }
        assertTrue("At most one eight-byte alignment block", padding <= 9)

        // RFC 8410 containers encode the raw seed/public bytes for the independent JDK provider.
        val keyFactory = KeyFactory.getInstance("Ed25519", "SunEC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(
            hex("302e020100300506032b657004220420") + secretBytes.copyOfRange(0, 32)))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(
            hex("302a300506032b6570032100") + publicBytes))
        verifySignature(privateKey, publicKey, "Ed25519", "SunEC")
        privateBlob.fill(0)
        secretBlock.fill(0)
        secretBytes.fill(0)
    }

    @Test fun rsaUnsupportedSizesAreRejected() {
        for (bits in listOf(-1, 0, 1024, 2049, 8192)) {
            val result = runCatching { SshKeyGenerator.generate(SshKeyGenerator.Request.Rsa(bits)) }
            assertTrue("Unsupported RSA size rejected", result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun verifyRsa(bits: Int) {
        val data = SshKeyGenerator.generate(SshKeyGenerator.Request.Rsa(bits), "  synthetic only  ")
        assertEquals(SshKeyData.ALGORITHM_RSA, data.algorithm)
        assertEquals(bits, data.keySize)
        val publicBlob = publicBlobAndCommonContract(data, "ssh-rsa")
        val publicReader = WireReader(publicBlob)
        assertEquals("ssh-rsa", publicReader.text())
        val exponent = positiveMpInt(publicReader.field())
        val modulus = positiveMpInt(publicReader.field())
        publicReader.requireEnd()
        assertEquals(BigInteger.valueOf(65537), exponent)
        assertEquals(bits, modulus.bitLength())

        val privateBlob = pem(data.privateKeyOpenSsh, "RSA PRIVATE KEY")
        val outer = DerReader(privateBlob)
        val sequence = DerReader(outer.value(0x30))
        outer.requireEnd()
        assertTrue("PKCS1 version zero", sequence.integer() == BigInteger.ZERO)
        val n = sequence.integer()
        val e = sequence.integer()
        val d = sequence.integer()
        val p = sequence.integer()
        val q = sequence.integer()
        val dP = sequence.integer()
        val dQ = sequence.integer()
        val qInv = sequence.integer()
        sequence.requireEnd()
        assertTrue("Private modulus and exponent match public key", n == modulus && e == exponent)
        assertTrue("CRT factors match modulus", p.multiply(q) == n)
        assertTrue("First CRT exponent matches", d.mod(p.subtract(BigInteger.ONE)) == dP)
        assertTrue("Second CRT exponent matches", d.mod(q.subtract(BigInteger.ONE)) == dQ)
        assertTrue("CRT coefficient matches", qInv.multiply(q).mod(p) == BigInteger.ONE)
        val keyFactory = KeyFactory.getInstance("RSA", "SunRsaSign")
        val privateKey = keyFactory.generatePrivate(RSAPrivateCrtKeySpec(n, e, d, p, q, dP, dQ, qInv))
        val publicKey = keyFactory.generatePublic(RSAPublicKeySpec(modulus, exponent))
        verifySignature(privateKey, publicKey, "SHA256withRSA", "SunRsaSign")
        privateBlob.fill(0)
    }

    private fun publicBlobAndCommonContract(data: SshKeyData, type: String): ByteArray {
        val fields = data.publicKeyOpenSsh.split(' ', limit = 3)
        assertEquals(3, fields.size)
        assertEquals(type, fields[0])
        assertEquals("synthetic only", fields[2])
        assertEquals("synthetic only", data.comment)
        assertEquals(SshKeyData.FORMAT_OPENSSH, data.format)
        assertEquals(SshKeyData.SCHEMA_V1, data.schema)
        val blob = Base64.getDecoder().decode(fields[1])
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertTrue("SHA256 fingerprint matches independent JDK calculation", fingerprint == data.fingerprintSha256)
        assertTrue("Codec preserves generated key data (key values suppressed)",
            SshKeyDataCodec.decode(SshKeyDataCodec.encode(data)) == data)
        val withUnknown = data.copy(additionalFields = mapOf("synthetic_future_field" to JsonPrimitive("kept")))
        assertTrue("Codec preserves unknown metadata and key bytes (values suppressed)",
            SshKeyDataCodec.decode(SshKeyDataCodec.encode(withUnknown)) == withUnknown)
        return blob
    }

    private fun verifySignature(privateKey: PrivateKey, publicKey: PublicKey, algorithm: String, provider: String) {
        val challenge = "Monica toolchain synthetic SSH compatibility challenge".toByteArray(StandardCharsets.UTF_8)
        val signer = Signature.getInstance(algorithm, provider)
        signer.initSign(privateKey)
        signer.update(challenge)
        val signature = signer.sign()
        val verifier = Signature.getInstance(algorithm, provider)
        verifier.initVerify(publicKey)
        verifier.update(challenge)
        assertTrue("Independent JDK verifies generated key pair", verifier.verify(signature))
        challenge[0] = (challenge[0].toInt() xor 1).toByte()
        verifier.initVerify(publicKey)
        verifier.update(challenge)
        assertFalse("Changed challenge rejected", verifier.verify(signature))
    }

    private fun pem(text: String, label: String): ByteArray {
        val lines = text.trim().lines()
        assertEquals("-----BEGIN $label-----", lines.first())
        assertEquals("-----END $label-----", lines.last())
        return Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
    }

    private fun positiveMpInt(bytes: ByteArray): BigInteger {
        check(bytes.isNotEmpty() && bytes[0].toInt() and 0x80 == 0) { "Expected positive SSH mpint" }
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            check(bytes[1].toInt() and 0x80 != 0) { "Noncanonical SSH mpint" }
        }
        return BigInteger(bytes).also { check(it.signum() > 0) }
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class WireReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position
        fun take(count: Int): ByteArray {
            check(count >= 0 && count <= remaining) { "Invalid SSH field length" }
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
        fun uint32(): Int {
            val b = take(4)
            val value = b.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255L) }
            check(value <= Int.MAX_VALUE) { "Unsupported SSH field length" }
            return value.toInt()
        }
        fun field(): ByteArray = take(uint32())
        fun text(): String = field().toString(StandardCharsets.UTF_8)
        fun requireEnd() { check(remaining == 0) { "Unexpected SSH trailing data" } }
    }

    private class DerReader(private val bytes: ByteArray) {
        private var position = 0
        private fun octet(): Int {
            check(position < bytes.size) { "Truncated DER" }
            return bytes[position++].toInt() and 255
        }
        fun value(tag: Int): ByteArray {
            check(octet() == tag) { "Unexpected DER tag" }
            val first = octet()
            val length = if (first < 128) first else {
                val count = first and 127
                check(count in 1..3) { "Invalid DER length" }
                var n = 0
                repeat(count) { index ->
                    val byte = octet()
                    check(index != 0 || byte != 0) { "Noncanonical DER length" }
                    n = (n shl 8) or byte
                }
                check(n >= 128) { "Noncanonical DER length" }
                n
            }
            check(length <= bytes.size - position) { "Truncated DER value" }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }
        fun integer(): BigInteger {
            val value = value(0x02)
            check(value.isNotEmpty() && value[0].toInt() and 128 == 0) { "Invalid DER positive integer" }
            if (value.size > 1 && value[0] == 0.toByte()) {
                check(value[1].toInt() and 128 != 0) { "Noncanonical DER integer" }
            }
            return BigInteger(value)
        }
        fun requireEnd() { check(position == bytes.size) { "Unexpected DER trailing data" } }
    }
}

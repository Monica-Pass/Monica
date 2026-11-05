package takagi.ru.monica.bitwarden.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey

/** Bitwarden's account fingerprint: HKDF-Expand-SHA256(SPKI, user ID), EFF long words. */
internal object BitwardenAccountFingerprint {
    fun publicKeyFromEncryptedPrivateKey(envelope: String, key: BitwardenCrypto.SymmetricCryptoKey): ByteArray {
        require(envelope.length in 3..32768 && envelope.startsWith("2."))
        val bytes = BitwardenCrypto.decrypt(envelope, key)
        try {
            // Android providers need not expose RSAPrivateCrtKey even for valid
            // PKCS8. Read the existing public components from its RSA structure.
            val info = PrivateKeyInfo.getInstance(bytes)
            require(info.privateKeyAlgorithm.algorithm == PKCSObjectIdentifiers.rsaEncryption)
            val privateKey = RSAPrivateKey.getInstance(info.parsePrivateKey())
            require(privateKey.modulus.signum() > 0 && privateKey.modulus.bitLength() in 2048..8192)
            require(privateKey.publicExponent > BigInteger.ONE && privateKey.publicExponent.testBit(0))
            return KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            ).encoded
        } finally {
            bytes.fill(0)
        }
    }

    fun phrase(userId: String, publicKeySpki: ByteArray, words: List<String>): String {
        require(userId.isNotBlank() && userId.length <= 128)
        require(publicKeySpki.size in 256..2048)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeySpki))
        require(publicKey is RSAPublicKey && publicKey.modulus.bitLength() in 2048..8192)
        require(words.size == 7776 && words.toSet().size == words.size)
        require(words.all { it.isNotBlank() && it.all { c -> c in 'a'..'z' || c == '-' } })
        // A single expand block is enough for the protocol's 32-byte output. HMAC hashes
        // a long SPKI key internally; adding HKDF Extract here would change the phrase.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(publicKeySpki, "HmacSHA256"))
        mac.update(userId.toByteArray(Charsets.UTF_8))
        val hash = mac.doFinal(byteArrayOf(1))
        return try {
            var number = BigInteger(1, hash)
            val radix = BigInteger.valueOf(words.size.toLong())
            List(5) {
                val parts = number.divideAndRemainder(radix)
                number = parts[0]
                words[parts[1].toInt()]
            }.joinToString("-")
        } finally {
            hash.fill(0)
        }
    }
}

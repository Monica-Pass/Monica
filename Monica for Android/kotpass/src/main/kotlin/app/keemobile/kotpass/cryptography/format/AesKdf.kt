package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.extensions.clear
import app.keemobile.kotpass.extensions.sha256
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object AesKdf {
    fun transformKey(
        key: ByteArray,
        seed: ByteArray,
        rounds: ULong
    ): ByteArray = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(seed, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        for (r in 0 until rounds.toLong()) {
            cipher.update(key, 0, 16, key, 0)
            cipher.update(key, 16, 16, key, 16)
        }
        key.sha256().also {
            key.clear()
        }
    } catch (e: GeneralSecurityException) {
        if (e is NoSuchAlgorithmException) {
            throw CryptoError.AlgorithmUnavailable("AES/ECB encryption is not supported in current environment.")
        } else {
            throw CryptoError.InvalidKey("Wrong KDF seed used for decryption.")
        }
    }
}

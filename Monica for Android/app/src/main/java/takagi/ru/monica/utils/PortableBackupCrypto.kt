package takagi.ru.monica.utils

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import takagi.ru.monica.data.BackupEncryptionFormat

/**
 * Portable ZIP envelope. V1 is frozen for existing clients. V2 authenticates its complete
 * fixed-size header and accepts one exact parameter profile before doing any expensive work.
 * See docs/storage/PORTABLE-BACKUP-ENCRYPTION-V2.md for the interoperable wire contract.
 */
internal object PortableBackupCrypto {
    const val V1_MAGIC = "MONICA_ENC_V1"
    const val V2_MAGIC = "MONICA_ENC_V2"
    const val FAMILY_PREFIX = "MONICA_ENC_V"
    const val V1_ITERATIONS = 100_000
    const val V2_ITERATIONS = 600_000
    const val V1_HEADER_SIZE = 57
    const val V2_HEADER_SIZE = 68
    private const val SALT_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    fun encrypt(
        plaintext: ByteArray,
        password: String,
        format: BackupEncryptionFormat = BackupEncryptionFormat.COMPATIBLE_V1,
    ): ByteArray {
        val random = SecureRandom()
        return encryptWithParameters(plaintext, password, format,
            ByteArray(SALT_SIZE).also(random::nextBytes), ByteArray(NONCE_SIZE).also(random::nextBytes))
    }

    /** Deterministic entry point for cross-language vectors; production always uses fresh randomness. */
    internal fun encryptWithParameters(
        plaintext: ByteArray,
        password: String,
        format: BackupEncryptionFormat,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        require(salt.size == SALT_SIZE && nonce.size == NONCE_SIZE) { "Invalid backup salt or nonce" }
        val enhanced = format == BackupEncryptionFormat.ENHANCED_V2
        if (enhanced) requireWellFormedPassword(password)
        val header = if (enhanced) {
            ByteBuffer.allocate(V2_HEADER_SIZE).put(V2_MAGIC.toByteArray(Charsets.US_ASCII))
                .put(1.toByte()).put(1.toByte()).put(0.toByte())
                .putInt(V2_ITERATIONS).putShort(SALT_SIZE.toShort()).putShort(NONCE_SIZE.toShort())
                .put(salt).put(nonce).array()
        } else V1_MAGIC.toByteArray(Charsets.US_ASCII) + salt + nonce
        val cipher = newCipher(Cipher.ENCRYPT_MODE, password, salt, nonce,
            if (enhanced) V2_ITERATIONS else V1_ITERATIONS)
        if (enhanced) cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    fun decrypt(envelope: ByteArray, password: String): ByteArray {
        require(envelope.size >= V1_MAGIC.length) { "Truncated backup header" }
        val magic = envelope.copyOfRange(0, V1_MAGIC.length).toString(Charsets.US_ASCII)
        val enhanced = when (magic) {
            V1_MAGIC -> false
            V2_MAGIC -> true
            else -> throw IllegalArgumentException("Unsupported backup format")
        }
        val headerSize = if (enhanced) V2_HEADER_SIZE else V1_HEADER_SIZE
        require(envelope.size >= headerSize + TAG_SIZE) { "Truncated encrypted backup" }
        var offset = V1_MAGIC.length
        if (enhanced) {
            // Validate every byte of the parameter profile before derivation or allocation from a length.
            val profile = ByteBuffer.wrap(envelope, offset, 11)
            require(profile.get().toInt() == 1 && profile.get().toInt() == 1 && profile.get().toInt() == 0 &&
                profile.int == V2_ITERATIONS && profile.short.toInt() == SALT_SIZE &&
                profile.short.toInt() == NONCE_SIZE) { "Unsupported backup encryption parameters" }
            requireWellFormedPassword(password)
            offset += 11
        }
        val salt = envelope.copyOfRange(offset, offset + SALT_SIZE)
        val nonce = envelope.copyOfRange(offset + SALT_SIZE, headerSize)
        val cipher = newCipher(Cipher.DECRYPT_MODE, password, salt, nonce,
            if (enhanced) V2_ITERATIONS else V1_ITERATIONS)
        if (enhanced) cipher.updateAAD(envelope, 0, headerSize)
        // No plaintext is returned until the complete GCM tag has been verified. Never retry as V1.
        return cipher.doFinal(envelope, headerSize, envelope.size - headerSize)
    }

    private fun newCipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray, iterations: Int): Cipher {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        chars.fill('\u0000')
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return Cipher.getInstance("AES/GCM/NoPadding").also {
                    it.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
                }
            } finally { keyBytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun requireWellFormedPassword(password: String) {
        var index = 0
        while (index < password.length) {
            val char = password[index++]
            when {
                Character.isHighSurrogate(char) -> require(index < password.length &&
                    Character.isLowSurrogate(password[index++])) { "Invalid password encoding" }
                Character.isLowSurrogate(char) -> throw IllegalArgumentException("Invalid password encoding")
            }
        }
    }
}

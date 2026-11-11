package takagi.ru.monica.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Versioned, bounded answer verifier. Legacy SHA is only a reader, never a new enrollment. */
internal object SecurityAnswerHash {
    private const val PREFIX = "sq2"
    private const val ROUNDS = 600_000
    private val legacy = Regex("[0-9a-fA-F]{64}")
    private val current = Regex("sq2\\$600000\\$[0-9a-f]{32}\\$[0-9a-f]{64}")
    fun isValid(value: String) = legacy.matches(value) || current.matches(value)
    fun isLegacy(value: String) = legacy.matches(value)
    fun canonical(value: String) = if (isLegacy(value.trim())) value.trim().lowercase() else value.trim()
    private fun normalize(answer: String) = answer.trim().lowercase()

    fun create(answer: String): String {
        require(normalize(answer).isNotEmpty() && answer.length <= 4096)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(answer, salt)
        return try { "$PREFIX\$$ROUNDS\$${PasswordKdf.hex(salt)}\$${PasswordKdf.hex(hash)}" }
        finally { hash.fill(0); salt.fill(0) }
    }

    fun verify(answer: String, encoded: String): Boolean {
        if (answer.length > 4096 || !isValid(encoded)) return false
        val expected: ByteArray
        val actual: ByteArray
        if (isLegacy(encoded)) {
            expected = PasswordKdf.unhex(encoded.lowercase(), 32)
            actual = MessageDigest.getInstance("SHA-256").digest(normalize(answer).toByteArray(Charsets.UTF_8))
        } else {
            val parts = encoded.split('$')
            val salt = PasswordKdf.unhex(parts[2], 16)
            expected = PasswordKdf.unhex(parts[3], 32)
            actual = try { derive(answer, salt) } finally { salt.fill(0) }
        }
        return try { MessageDigest.isEqual(expected, actual) }
        finally { expected.fill(0); actual.fill(0) }
    }

    private fun derive(answer: String, salt: ByteArray): ByteArray {
        val chars = ("monica-security-answer-v2:" + normalize(answer)).toCharArray()
        val spec = PBEKeySpec(chars, salt, ROUNDS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
}
